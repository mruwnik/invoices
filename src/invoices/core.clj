(ns invoices.core
  (:require [invoices.pdf :as pdf]
            [invoices.settings :refer [invoices]]
            [invoices.jira :refer [prev-timesheet]]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str])
  (:gen-class))

(defn invoice-number [when number]
  (->> [(or number 1) (-> when .getMonthValue) (-> when .getYear)] (map str) (str/join "/")))

(defn calc-part-time [when creds {base :base per-day :per-day}]
  (let [{worked :worked total :required} (prev-timesheet when creds)
        hourly (/ (* base 8) (* total per-day))]
    (float (* hourly worked))))

(defn calc-hourly [when creds {hourly :hourly}]
    (-> (prev-timesheet when creds) :worked (* hourly)))


(defn set-price [when creds item]
  (cond
    (contains? item :hourly) (assoc item :netto (calc-hourly when creds item))
    (contains? item :base) (assoc item :netto (calc-part-time when creds item))
    (not (contains? item :netto)) (assoc item :netto 0)
    :else item))

(defn for-month [when {seller :seller buyer :buyer items :items creds :credentials} & [number]]
  (pdf/render seller buyer
              (map (partial set-price when creds) items)
              (pdf/last-working-day when)
              (invoice-number when number)))

(defn get-invoices [nips config]
  (if (seq nips)
    (filter #(some #{(-> % :buyer :nip str)} nips) (invoices config))
    (invoices config)))


(def cli-options
  [["-n" "--number NUMBER" "Invoice number. In the case of multiple invoices, they will have subsequent numbers"
    :default 1
    :parse-fn #(Integer/parseInt %)]
   ["-w" "--when DATE" "The month for which to generate the invoice"
    :default (java.time.LocalDate/now)
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
  "I don't do a whole lot ... yet."
  [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options) (help summary)
      errors (exit -1 (str/join "\n" errors))
      (not= 1 (count arguments)) (exit -1 "No config file provided"))
    (println "Generating invoices")
    (doseq [[i invoice] (map-indexed vector (get-invoices (:company options) (first arguments)))]
      (for-month (:when options) invoice (+ i (:number options))))
    ))
