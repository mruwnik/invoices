(ns invoices.core
  (:require [invoices.pdf :as pdf]
            [invoices.settings :refer [invoices]]
            [invoices.jira :refer [prev-timesheet prev-month]]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str]
            [postal.core :refer [send-message]])
  (:gen-class))

(defn invoice-number [when number]
  (->> [(or number 1) (-> when .getMonthValue) (-> when .getYear)] (map str) (str/join "/")))

(defn parse-custom [work-log func]
  (cond
    (and (list? func) (some #{(first func)} '(+ - * /))) (apply (resolve (first func))
                                                                (map (partial parse-custom work-log) (rest func)))
    (list? func) (throw (IllegalArgumentException. (str "Invalid functor provided: " (first func))))
    (some #{func} '(:worked :required :to :from)) (func work-log)
    :else func))

(defn calc-part-time [{worked :worked total :required} {base :base per-day :per-day}]
  (let [hourly (/ (* base 8) (* total per-day))]
    (float (* hourly worked))))

(defn calc-hourly [{worked :worked} {hourly :hourly}]
    (* worked hourly))

(defn calc-custom [worked {function :function}]
  (parse-custom worked function))


(defn set-price [worked item]
  (cond
    (contains? item :function) (assoc item :netto (calc-custom worked item))
    (contains? item :hourly) (assoc item :netto (calc-hourly worked item))
    (contains? item :base) (assoc item :netto (calc-part-time worked item))
    (not (contains? item :netto)) (assoc item :netto 0)
    :else item))

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

(defn date-applies? [when {to :to from :from}]
  (and (or (nil? to) (-> when .toString (compare to) (< 0)))
       (or (nil? from) (-> when .toString (compare from) (>= 0)))))

(defn for-month [when {seller :seller buyer :buyer items :items creds :credentials font-path :font-path} & [number]]
  (->>
   (pdf/render seller buyer
               (->> items
                    (filter (partial date-applies? when))
                    (map (partial set-price (prev-timesheet when creds))))
               (pdf/last-working-day when)
               (invoice-number when number)
               font-path)
   (send-email (:email buyer) (:email seller) creds)))

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
