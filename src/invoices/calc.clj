(ns invoices.calc)

(defn round [val]
  (double (/ (Math/round (* val 100.0)) 100)))

(def valid-vat-values
  "Canonical set of accepted `:vat` item values. Integer rates map to the
  Polish VAT brackets; keywords map to the FA(3) non-rate classifications:
    :zw    — zwolnione (domestic exempt under Polish law)
    :np    — not subject to Polish VAT, place of supply outside RP
             (art. 28b for services to non-EU buyers → FA(3) P_12='np I')
    :np-eu — not subject under art. 100 ust. 1 pkt 4 (intra-EU B2B
             services that must appear on VAT-UE recap → P_12='np II')
    nil    — legacy alias for :zw (back-compat for pre-:zw configs).

  Anything outside this set is a typo (e.g. `:np-us` for `:np`). We
  reject it loudly here instead of letting the FA(3) encoder silently
  route to the zw-exempt bucket — misclassification of an np item is a
  legal problem (wrong JPK-V7, wrong VAT-UE), not a rendering problem."
  #{23 22 8 7 5 4 3 0 :zw :np :np-eu nil})

(defn validate-item-vat!
  "Throw on unknown `:vat` values so typos surface at price-assembly
  time (before PDF rendering, before FA(3) XML emission, before KSeF
  submission). The rationale lives on `valid-vat-values`."
  [{vat-level :vat :as item}]
  (when-not (contains? valid-vat-values vat-level)
    (throw (ex-info
             (str "Unknown :vat value: " (pr-str vat-level)
                  ". Valid values: integer rates (23, 22, 8, 7, 5, 4, 3, 0),"
                  " :zw, :np, :np-eu, or nil.")
             {:vat vat-level :item item}))))

(defn validate-item-quantity!
  "Throw if `:quantity` is set to anything other than nil, 1, or \"1\".

  The FA(3) encoder pins P_8B (quantity) to 1 because this codebase
  treats each item's `:netto` as the final line total, not a unit price
  — multiplying by a quantity on the wire would double-count. Allowing
  `:quantity 2` to silently serialize as P_8B=1 would be a quiet data-
  loss bug, so we fail loud instead. If true multi-unit pricing is ever
  needed, both `:netto` semantics AND P_8B/P_9A wiring must change
  together — this guard is what surfaces that work."
  [{q :quantity :as item}]
  (when-not (contains? #{nil 1 "1"} q)
    (throw (ex-info
             (str "Unsupported :quantity value: " (pr-str q)
                  ". This codebase treats :netto as the final line total,"
                  " so :quantity must be nil, 1, or \"1\". If you need"
                  " multi-unit pricing, change :netto to a unit price and"
                  " update fa-wiersz P_8B/P_9A together.")
             {:quantity q :item item}))))

(defn validate-item!
  "Run all per-item invariants. Called from `set-price` so that every
  pricing path — custom function, hourly, part-time, brutto→netto,
  naked :netto — funnels through the same sanity gate before the item
  reaches rendering."
  [item]
  (validate-item-vat! item)
  (validate-item-quantity! item)
  item)

(defn vat [{netto :netto vat-level :vat}]
  (if (number? vat-level)
    (* netto (/ vat-level 100))
    0))

(defn brutto [{netto :netto :as item}] (round (+ netto (vat item))))
(defn netto [{brutto :brutto vat :vat}] (/ (* brutto 100) (+ 100 vat)))

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
  "Set the net price for the given item, calculating it from a worklog if
  applicable. Every path funnels through `validate-item!` afterwards so
  typos in `:vat` and unsupported `:quantity` values fail loud before
  the item reaches PDF or FA(3) rendering."
  [worked item]
  (validate-item!
    (cond
      (contains? item :function) (assoc item :netto (calc-custom worked item))
      (contains? item :hourly) (assoc item :netto (calc-hourly worked item))
      (contains? item :base) (assoc item :netto (calc-part-time worked item))
      (contains? item :brutto) (assoc item :netto (netto item))
      (not (contains? item :netto)) (assoc item :netto 0)
      :else item)))
