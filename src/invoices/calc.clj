(ns invoices.calc)

(defn round [val]
  (double (/ (Math/round (* val 100.0)) 100)))

(defn vat [{netto :netto vat-level :vat}]
  (if-not vat-level 0 (* netto (/ vat-level 100))))

(defn brutto [{netto :netto :as item}] (round (+ netto (vat item))))

(defn parse-custom
  "Parse the given function definition and execute it with the given `worklog`."
  [work-log func]
  (cond
    (and (list? func) (some #{(first func)} '(+ - * /))) (apply (resolve (first func))
                                                                (map (partial parse-custom work-log) (rest func)))
    (some #{func} '(:worked :required)) (func work-log)
    (or (list? func) (not (number? func))) (throw (IllegalArgumentException. (str "Invalid functor provided: " (first func))))
    :else func))

(defn calc-part-time [{worked :worked total :required} {base :base per-day :per-day}]
  (when (and worked total)
    (let [hourly (/ (* base 8) (* total per-day))]
      (float (* hourly worked)))))

(defn calc-hourly [{worked :worked} {hourly :hourly}]
  (when worked (* worked hourly)))

(defn calc-custom [worked {function :function}]
  (parse-custom worked function))

(defn set-price
  "Set the net price for the given item, calculating it from a worklog if applicable."
  [worked item]
  (cond
    (contains? item :function) (assoc item :netto (calc-custom worked item))
    (contains? item :hourly) (assoc item :netto (calc-hourly worked item))
    (contains? item :base) (assoc item :netto (calc-part-time worked item))
    (not (contains? item :netto)) (assoc item :netto 0)
    :else item))
