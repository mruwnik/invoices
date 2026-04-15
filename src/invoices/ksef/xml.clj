(ns invoices.ksef.xml
  "FA(3) XML generator for KSeF submissions.

  Produces XML conforming to the FA(3) schema
  (ksef-docs/faktury/schemy/FA/schemat_FA(3)_v1-0E.xsd) with targetNamespace
  http://crd.gov.pl/wzor/2025/06/25/13775/. The public entry point is
  `invoice->fa3-xml`; it takes a map with :seller, :buyer, :items, :number
  and :date (LocalDate) and returns a string."
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str])
  (:import [java.math RoundingMode]
           [java.time LocalDate ZoneOffset ZonedDateTime]
           [java.time.format DateTimeFormatter]))

(def fa-ns "http://crd.gov.pl/wzor/2025/06/25/13775/")

(xml/alias-uri 'fa fa-ns)

;; ---------------- formatting helpers ----------------

(defn- amount-str
  "Render a number as a period-decimal string with exactly two digits of
  precision (HALF_UP), locale-independent. XSD type TKwotowy accepts up to 2
  fraction digits; bigdec+setScale guarantees the right shape."
  [n]
  (-> (bigdec n) (.setScale 2 RoundingMode/HALF_UP) .toPlainString))

(defn- qty-str [n]
  (-> (bigdec n) (.setScale 6 RoundingMode/HALF_UP) .toPlainString))

(defn- iso-date [^LocalDate d]
  (.format d DateTimeFormatter/ISO_LOCAL_DATE))

(defn- utc-now []
  (-> (ZonedDateTime/now ZoneOffset/UTC)
      (.format DateTimeFormatter/ISO_INSTANT)))

(defn- nip-str
  "Normalize NIP to the 10-digit form required by the TNrNIP pattern. Accepts
  numbers and strings with spaces/dashes."
  [nip]
  (-> nip str (str/replace #"[^0-9]" "")))

;; ---------------- VAT rate logic ----------------
;;
;; FA(3) has two distinct "not subject to Polish VAT" classifications in
;; the TStawkaPodatku enum, and routes each to a different summary bucket:
;;
;;   :np    → P_12="np I",  bucket P_13_8.  For services/goods delivered
;;            outside Poland with place of supply determined by buyer
;;            location (art. 28b ust. 1 ustawy o VAT when buyer is non-EU,
;;            or any other case of "dostawy towarów oraz świadczenia usług
;;            poza terytorium kraju" that does NOT fall under art. 100 ust.
;;            1 pkt 4 or OSS). The typical Polish-contractor-to-US case.
;;
;;   :np-eu → P_12="np II", bucket P_13_9. Reserved for the specific case
;;            of B2B services to an EU-VAT-registered buyer that must be
;;            listed on the VAT-UE recapitulative statement per art. 100
;;            ust. 1 pkt 4 ustawy o VAT. Legally distinct from :np.
;;
;; Neither has a corresponding P_14_N bucket — no Polish VAT is owed, so
;; nothing to report.

(def ^:private vat-code
  "Map from invoice :vat to the FA(3) P_12 stawka-podatku value. Integer
  values map to rate strings; keyword values map to the enum labels."
  {23 "23" 22 "22" 8 "8" 7 "7" 5 "5" 4 "4" 3 "3" 0 "0 KR"
   :np "np I" :np-eu "np II" :zw "zw"})

(def ^:private valid-vat-values
  "Canonical set of accepted `:vat` values. Anything outside this set is
  a typo (e.g. `:np-us` for `:np`) and must fail loud instead of silently
  routing to the zw-exempt bucket — misclassification of a np item is a
  legal problem, not a rendering problem."
  (conj (set (keys vat-code)) nil))

(defn- validate-item-vat!
  "Throw on unknown `:vat` values so typos surface at submission time."
  [{vat :vat :as item}]
  (when-not (contains? valid-vat-values vat)
    (throw (ex-info
             (str "Unknown :vat value: " (pr-str vat)
                  ". Valid values: integer rates (23, 22, 8, 7, 5, 4, 3, 0),"
                  " :zw, :np, :np-eu, or nil.")
             {:vat vat :item item}))))

(defn- item-vat-code [{vat :vat}]
  (or (vat-code vat) "zw"))

(def ^:private vat-bucket
  "Item :vat → summary-bucket key. Integers for rate-based buckets,
  keywords for the non-standard classifications."
  {23 :std 22 :std
   8  :red1 7 :red1
   5  :red2
   4  :taxi 3 :taxi
   0  :zero-kr
   :np :np-out
   :np-eu :np-eu-art100
   :zw :exempt})

(defn- item-bucket [{vat :vat}]
  (get vat-bucket vat :exempt))

(defn- item-netto [{netto :netto}]
  (bigdec (or netto 0)))

(defn- item-vat-amount [{netto :netto vat :vat}]
  (if (and (integer? vat) (pos? vat))
    (-> (bigdec (or netto 0))
        (.multiply (bigdec vat))
        (.divide (bigdec 100) 2 RoundingMode/HALF_UP))
    (bigdec 0)))

(defn- np-item?
  "True iff the item's VAT classification is a 'not subject to Polish VAT'
  keyword (either :np or :np-eu)."
  [{vat :vat}]
  (or (= vat :np) (= vat :np-eu)))

(defn- item-brutto [item]
  (.add (item-netto item) (item-vat-amount item)))

(defn- sum-bd
  ([] (bigdec 0))
  ([^java.math.BigDecimal a ^java.math.BigDecimal b] (.add a b)))

(defn- bucket-sums [items]
  (let [by-bucket (group-by item-bucket items)
        netto-of  (fn [bs] (-> (reduce sum-bd (bigdec 0) (map item-netto bs))
                               (.setScale 2 RoundingMode/HALF_UP)))
        vat-of    (fn [bs] (-> (reduce sum-bd (bigdec 0) (map item-vat-amount bs))
                               (.setScale 2 RoundingMode/HALF_UP)))]
    (into {}
          (for [[k bs] by-bucket]
            [k {:netto (netto-of bs) :vat (vat-of bs)}]))))

(defn- has-np? [items]
  (boolean (some #(= :np (:vat %)) items)))

(defn- has-np-eu? [items]
  (boolean (some #(= :np-eu (:vat %)) items)))

(defn- total-brutto [items]
  (-> (reduce sum-bd (bigdec 0) (map item-brutto items))
      (.setScale 2 RoundingMode/HALF_UP)))

;; ---------------- section builders ----------------

(defn- naglowek []
  (xml/element ::fa/Naglowek {}
    (xml/element ::fa/KodFormularza
                 {:kodSystemowy "FA (3)" :wersjaSchemy "1-0E"}
                 "FA")
    (xml/element ::fa/WariantFormularza {} "3")
    (xml/element ::fa/DataWytworzeniaFa {} (utc-now))
    (xml/element ::fa/SystemInfo {} "invoices-clj")))

(defn- adres [{address :address country :country}]
  (xml/element ::fa/Adres {}
    (xml/element ::fa/KodKraju {} (or country "PL"))
    (xml/element ::fa/AdresL1 {} (str (or address "brak")))))

(defn- podmiot1 [seller]
  (xml/element ::fa/Podmiot1 {}
    (xml/element ::fa/DaneIdentyfikacyjne {}
      (xml/element ::fa/NIP {} (nip-str (:nip seller)))
      (xml/element ::fa/Nazwa {} (str (:name seller))))
    (adres seller)))

(def ^:private eu-country-codes
  "ISO country codes of EU VAT-UE participants, including XI for Northern
  Ireland (which retains EU VAT on goods under the Windsor Framework).
  Sourced from `TKodyKrajowUE` in the FA(3) XSD (EL is Greece's EU-VAT code,
  not GR; PL is still valid as a buyer code but we use NIP for PL sellers
  to PL buyers)."
  #{"AT" "BE" "BG" "CY" "CZ" "DE" "DK" "EE" "EL" "ES" "FI" "FR" "HR"
    "HU" "IE" "IT" "LT" "LU" "LV" "MT" "NL" "PL" "PT" "RO" "SE" "SI"
    "SK" "XI"})

(defn- eu-buyer? [{country :country}]
  (contains? (disj eu-country-codes "PL") country))

(defn- strip-country-prefix
  "If `nip` begins with `cc` (the buyer's country code), drop that prefix.
  TNrVatUE expects the numeric part; KSeF rejects documents where KodUE
  is duplicated inside NrVatUE."
  [nip cc]
  (let [s (str/replace (str nip) #"[^0-9A-Za-z+*]" "")
        up (.toUpperCase s)]
    (if (and cc (str/starts-with? up cc))
      (subs s (count cc))
      s)))

(defn- dane-identyfikacyjne-podmiot2
  "Choose one of the four FA(3) Podmiot2 identifier shapes based on the
  buyer map. Priority order:

    1. Non-PL EU country with :nip → <KodUE>+<NrVatUE> (VAT-UE id)
    2. Non-PL non-EU country with :nip → <KodKraju>+<NrID> (foreign tax id)
    3. :nip present (PL or unspecified country) → <NIP> (TNrNIP pattern)
    4. No :nip → <BrakID>1</BrakID>

  The nested Nazwa element comes after the identifier choice per
  TPodmiot2 schema sequence. Caller is expected to pass a buyer whose
  `:country` has already been upper-cased (see `podmiot2`)."
  [buyer]
  (let [{:keys [nip country name]} buyer]
    (xml/element ::fa/DaneIdentyfikacyjne {}
      (concat
        (cond
          (and nip (eu-buyer? {:country country}))
          [(xml/element ::fa/KodUE {} country)
           (xml/element ::fa/NrVatUE {} (strip-country-prefix nip country))]

          (and nip country (not= "PL" country))
          [(xml/element ::fa/KodKraju {} country)
           (xml/element ::fa/NrID {} (str nip))]

          nip
          [(xml/element ::fa/NIP {} (nip-str nip))]

          :else
          [(xml/element ::fa/BrakID {} "1")])
        [(xml/element ::fa/Nazwa {} (str name))]))))

(defn- podmiot2 [buyer]
  (let [buyer (update buyer :country #(some-> % str/upper-case))]
    (xml/element ::fa/Podmiot2 {}
      (dane-identyfikacyjne-podmiot2 buyer)
      (adres buyer)
      (xml/element ::fa/JST {} "2")
      (xml/element ::fa/GV {} "2"))))

(defn- bucket-sum-elements
  "Emit the optional P_13_N/P_14_N groups in the exact order required by the
  schema (P_13_1/P_14_1 → … → P_13_5/P_14_5 → P_13_6_1 → P_13_7 → P_13_8 →
  P_13_9). Elements are only emitted when the bucket is non-empty. Buckets
  P_13_8 and P_13_9 are the 'not subject to Polish VAT' sums; they have
  no corresponding P_14_N because no Polish VAT is owed on them."
  [sums]
  (let [{:keys [std red1 red2 taxi zero-kr exempt np-out np-eu-art100]} sums]
    (remove nil?
      [(when std
         (list (xml/element ::fa/P_13_1 {} (amount-str (:netto std)))
               (xml/element ::fa/P_14_1 {} (amount-str (:vat   std)))))
       (when red1
         (list (xml/element ::fa/P_13_2 {} (amount-str (:netto red1)))
               (xml/element ::fa/P_14_2 {} (amount-str (:vat   red1)))))
       (when red2
         (list (xml/element ::fa/P_13_3 {} (amount-str (:netto red2)))
               (xml/element ::fa/P_14_3 {} (amount-str (:vat   red2)))))
       (when taxi
         (list (xml/element ::fa/P_13_4 {} (amount-str (:netto taxi)))
               (xml/element ::fa/P_14_4 {} (amount-str (:vat   taxi)))))
       (when zero-kr
         (xml/element ::fa/P_13_6_1 {} (amount-str (:netto zero-kr))))
       (when exempt
         (xml/element ::fa/P_13_7 {} (amount-str (:netto exempt))))
       (when np-out
         (xml/element ::fa/P_13_8 {} (amount-str (:netto np-out))))
       (when np-eu-art100
         (xml/element ::fa/P_13_9 {} (amount-str (:netto np-eu-art100))))])))

(defn- adnotacje
  "Minimal required Adnotacje block. P_19 flag is set based on whether any
  item claims a Polish-law zwolnienie (`:vat nil` for back-compat, or the
  explicit `:vat :zw` keyword). Items classified as `:np` or `:np-eu` are
  NOT zwolnione — those are distinct legal classifications and P_19N must
  remain set for pure-np invoices.

  P_18 stays `\"2\"` for np-classified invoices because art. 28b is a
  place-of-supply rule, not a reverse-charge under art. 17. Distinct
  concepts; don't conflate them."
  [items]
  (let [has-exempt? (boolean (some #(or (nil? (:vat %)) (= :zw (:vat %))) items))
        zwolnienie (if has-exempt?
                     (xml/element ::fa/Zwolnienie {}
                       (xml/element ::fa/P_19 {} "1")
                       (xml/element ::fa/P_19C {} "Zwolnienie z VAT"))
                     (xml/element ::fa/Zwolnienie {}
                       (xml/element ::fa/P_19N {} "1")))]
    (xml/element ::fa/Adnotacje {}
      (xml/element ::fa/P_16 {} "2")
      (xml/element ::fa/P_17 {} "2")
      (xml/element ::fa/P_18 {} "2")
      (xml/element ::fa/P_18A {} "2")
      zwolnienie
      (xml/element ::fa/NoweSrodkiTransportu {}
        (xml/element ::fa/P_22N {} "1"))
      (xml/element ::fa/P_23 {} "2")
      (xml/element ::fa/PMarzy {}
        (xml/element ::fa/P_PMarzyN {} "1")))))

(def ^:private np-legal-basis
  "Embedded in the FA(3) XML DodatkowyOpis so the KSeF-stored document
  itself carries the legal citation, independently of the PDF. This is
  what the buyer sees when they retrieve the invoice from KSeF."
  "Usługa nie podlega opodatkowaniu VAT na terytorium RP — miejsce świadczenia ustalone zgodnie z art. 28b ustawy z dnia 11 marca 2004 r. o podatku od towarów i usług")

(def ^:private np-eu-legal-basis
  "np II companion citation — art. 100 ust. 1 pkt 4 indicates the invoice
  must also appear on the VAT-UE recapitulative statement."
  "Świadczenie usług, o których mowa w art. 100 ust. 1 pkt 4 ustawy z dnia 11 marca 2004 r. o podatku od towarów i usług — miejsce świadczenia poza terytorium RP, ustalone zgodnie z art. 28b ustawy")

(defn- dodatkowy-opis-elements
  "Emit DodatkowyOpis (klucz/wartość) entries documenting the legal basis
  whenever any np or np-eu item is present. One entry per distinct
  classification. Omitted entirely when no np/np-eu items exist."
  [items]
  (remove nil?
    [(when (has-np? items)
       (xml/element ::fa/DodatkowyOpis {}
         (xml/element ::fa/Klucz {} "Podstawa prawna (np I)")
         (xml/element ::fa/Wartosc {} np-legal-basis)))
     (when (has-np-eu? items)
       (xml/element ::fa/DodatkowyOpis {}
         (xml/element ::fa/Klucz {} "Podstawa prawna (np II)")
         (xml/element ::fa/Wartosc {} np-eu-legal-basis)))]))

(defn- fa-wiersz [idx item]
  (let [netto (.setScale (item-netto item) 2 RoundingMode/HALF_UP)]
    (xml/element ::fa/FaWiersz {}
      (xml/element ::fa/NrWierszaFa {} (str (inc idx)))
      (xml/element ::fa/P_7 {} (str (:title item)))
      (xml/element ::fa/P_8A {} (or (:unit item) "szt."))
      ;; P_8B is pinned to 1 because the rest of the codebase treats every
      ;; item's `:netto` as the final line total, not a unit price — so
      ;; multiplying by a quantity here would double-count. `:quantity` is
      ;; validated upstream in calc.clj to reject values other than nil/1.
      (xml/element ::fa/P_8B {} (qty-str 1))
      (xml/element ::fa/P_9A {} (amount-str netto))
      (xml/element ::fa/P_11 {} (amount-str netto))
      (xml/element ::fa/P_12 {} (item-vat-code item)))))

(defn- warn-item-currency-mismatch!
  "KSeF FA(3) is strictly single-currency per document (one KodWaluty), so
  per-item `:currency` that differs from the invoice-level currency cannot
  be faithfully represented. Print a warning and ignore — silently dropping
  the mismatch would be worse than crashing, crashing would be worse than
  warning. Callers get the informational signal without breaking live
  pipelines."
  [invoice-currency items]
  (doseq [[i {c :currency}] (map-indexed vector items)
          :when (and c (not= c invoice-currency))]
    (println (format "  [ksef/xml] WARN: item %d has :currency %s but invoice KodWaluty is %s — item currency will be ignored (KSeF is strictly single-currency)"
                     (inc i) (pr-str c) (pr-str invoice-currency)))))

(defn- fa-body [{:keys [items number date place currency]}]
  (let [kod-waluty (or currency "PLN")
        _ (warn-item-currency-mismatch! kod-waluty items)
        sums    (bucket-sums items)
        brutto  (total-brutto items)
        bucket-els (bucket-sum-elements sums)
        opis-els   (dodatkowy-opis-elements items)]
    (xml/element ::fa/Fa {}
      (concat
        [(xml/element ::fa/KodWaluty {} kod-waluty)
         (xml/element ::fa/P_1 {} (iso-date date))]
        (when place [(xml/element ::fa/P_1M {} place)])
        [(xml/element ::fa/P_2 {} (str number))
         (xml/element ::fa/P_6 {} (iso-date date))]
        (mapcat (fn [x] (if (sequential? x) x [x])) bucket-els)
        [(xml/element ::fa/P_15 {} (amount-str brutto))
         (adnotacje items)
         (xml/element ::fa/RodzajFaktury {} "VAT")]
        opis-els
        (map-indexed fa-wiersz items)))))

;; ---------------- public API ----------------

(defn invoice->fa3-xml
  "Render an invoice map to an FA(3) XML string.

  Required keys: :seller, :buyer, :items, :number, :date (LocalDate).
  Optional keys:
    :place     — miejsce wystawienia (P_1M).
    :currency  — ISO 4217 code for KodWaluty. Defaults to \"PLN\" when
                 absent. The FA(3) XSD's TKodWaluty enum is closed, so an
                 unrecognized code will XSD-fail loudly.

  Item `:vat` keys:
    integer  — a concrete VAT rate (23, 22, 8, 7, 5, 4, 3, 0). 0 maps to
               the domestic-0% bucket (`0 KR` / P_13_6_1).
    :zw      — zwolnione, domestic exempt under Polish law.
    nil      — legacy alias for :zw.
    :np      — not subject to Polish VAT, place of supply outside RP
               (typically art. 28b for services to non-EU buyers). Routes
               to P_12=\"np I\", bucket P_13_8, no P_14.
    :np-eu   — not subject to Polish VAT under art. 100 ust. 1 pkt 4
               (intra-EU B2B services that must appear on VAT-UE recap).
               Routes to P_12=\"np II\", bucket P_13_9, no P_14.

  When any `:np`/`:np-eu` item is present, a `DodatkowyOpis` entry is
  emitted citing the legal basis so the KSeF-stored document carries the
  annotation independently of the PDF."
  [{:keys [seller buyer items number date] :as invoice}]
  (run! validate-item-vat! items)
  (let [doc (xml/element ::fa/Faktura {}
              (naglowek)
              (podmiot1 seller)
              (podmiot2 buyer)
              (fa-body invoice))]
    (xml/emit-str doc)))
