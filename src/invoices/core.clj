(ns invoices.core
  (:require [invoices.pdf :as pdf]
            [invoices.settings :refer [invoices]]
            [invoices.timesheets :refer [timesheets]]
            [invoices.time :refer [prev-month last-working-day date-applies?]]
            [invoices.calc :refer [set-price]]
            [invoices.email :as email]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str]
            [clojure.java.shell :refer [sh]])
  (:gen-class))

(defn invoice-number [when number]
  (->> [(or number 1) (-> when .getMonthValue) (-> when .getYear)] (map str) (str/join "/")))

(defn run-callback [file callback]
  (let [command (concat callback [file])
        str-command (str/join " " command)
        result (apply sh command)]
    (if (= (:exit result) 0)
      (println "    *" str-command)
      (println "    X" str-command ":\n" (:err result)))
    (assoc result :command str-command)))

(defn run-callbacks [invoice callbacks]
  (doall (map (partial run-callback invoice) callbacks)))

(defn for-month [{seller :seller buyer :buyer smtp :smtp callbacks :callbacks :as invoice} when & [number font]]
  (let [file (pdf/render invoice (last-working-day when) (invoice-number when number))]
    (email/send-invoice file (:email buyer) smtp)
    (run-callbacks file callbacks)))

(defn item-price [worklogs item]
  (-> item :worklog (worklogs) (set-price item)))

(defn calc-prices [invoice worklogs]
  (update invoice :items (partial map (partial item-price worklogs))))

(defn prepare-invoice [{seller :seller font :font-path} month worklogs invoice]
  (-> invoice
      (assoc :seller seller)
      (assoc :font-path font)
      (calc-prices worklogs)))

(defn process-invoices [{invoices :invoices :as config} month worklogs]
  (let [invoices (map (partial prepare-invoice config month worklogs) invoices)]
    (doseq [[i invoice] (map-indexed vector invoices)]
      (for-month invoice month (inc i))
      (println))))

(def cli-options
  [["-n" "--number NUMBER" "Invoice number. In the case of multiple invoices, they will have subsequent numbers"
    :default 1
    :parse-fn #(Integer/parseInt %)]
   ["-w" "--when DATE" "The month for which to generate the invoice"
    :default (-> (java.time.LocalDate/now) prev-month)
    :parse-fn #(java.time.LocalDate/parse %)]
   ;; A non-idempotent option (:default is applied first)
   ["-c" "--company NIP" "companies for which to generate invoices. All, if not provided"
    :default []
    :assoc-fn (fn [m k v] (update m k conj v))]
   ["-h" "--help"]])

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn help [summary]
  (->> ["Generate invoices for preconfigured companies, provided via a config file" ""
        "Usage: faktury [options] <config file>" ""
        summary]
       (str/join "\n")
       (exit 0)))

(defn -main
  "Generate invoice pdfs"
  [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options) (help summary)
      errors (exit -1 (str/join "\n" errors))
      (not= 1 (count arguments)) (exit -1 "No config file provided"))

    (println "Generating invoices")
    (let [month (java.time.LocalDate/now)
          config (-> "config.edn" (invoices month))
          worklogs (timesheets month (:worklogs config))]
      (process-invoices config month worklogs)))
  (shutdown-agents))
