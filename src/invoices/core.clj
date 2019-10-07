(ns invoices.core
  (:require [invoices.pdf :as pdf]
            [invoices.settings :refer [invoices]]
            [invoices.jira :refer [prev-timesheet]]
            [invoices.time :refer [prev-month last-working-day date-applies?]]
            [invoices.calc :refer [set-price]]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str]
            [clojure.java.shell :refer [sh]]
            [postal.core :refer [send-message]])
  (:gen-class))

(defn invoice-number [when number]
  (->> [(or number 1) (-> when .getMonthValue) (-> when .getYear)] (map str) (str/join "/")))

(defn send-email [to from {smtp :smtp} invoice]
  (when (not-any? nil? [to from smtp invoice])
    (->>
     (send-message smtp {:from from
                         :to [to]
                         :subject invoice
                         :body [{:type :attachment
                                 :content (java.io.File. (str invoice ".pdf"))
                                 :content-type "application/pdf"}]})
     :error (= :SUCCESS)
     (println " - email sent: "))))

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


(defn render-month [when {seller :seller buyer :buyer items :items creds :credentials font-path :font-path} number]
  (pdf/render seller buyer
              (->> items
                   (filter (partial date-applies? when))
                   (map (partial set-price (prev-timesheet when creds))))
              (last-working-day when)
              (invoice-number when number)
              font-path))

(defn for-month [when {seller :seller buyer :buyer creds :credentials callbacks :callbacks :as invoice} & [number]]
  (let [file (-> when (render-month invoice number) (str ".pdf") java.io.File. .getAbsolutePath)]
    (send-email (:email buyer) (:email seller) creds file)
    (run-callbacks file callbacks)))

(defn get-invoices [nips config]
  (if (seq nips)
    (filter #(some #{(-> % :buyer :nip str)} nips) (invoices config))
    (invoices config)))


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
    (doseq [[i invoice] (map-indexed vector (get-invoices (:company options) (first arguments)))]
      (for-month (:when options) invoice (+ i (:number options)))
      (println)))
  (shutdown-agents))
