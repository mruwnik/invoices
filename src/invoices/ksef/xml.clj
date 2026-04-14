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

(def ^:private vat-code
  "Map from invoice :vat (integer or nil) to the FA(3) stawka-podatku code."
  {23 "23" 22 "22" 8 "8" 7 "7" 5 "5" 4 "4" 3 "3" 0 "0 KR"})

(defn- item-vat-code [{vat :vat}]
  (or (vat-code vat) "zw"))

(def ^:private vat-bucket
  "Groups that map to different P_13_N / P_14_N summary fields."
  {23 :std 22 :std
   8  :red1 7 :red1
   5  :red2
   4  :taxi 3 :taxi
   0  :zero-kr})

(defn- item-bucket [{vat :vat}]
  (get vat-bucket vat :exempt))

(defn- item-netto [{netto :netto}]
  (bigdec (or netto 0)))

(defn- item-vat-amount [{netto :netto vat :vat}]
  (if (and vat (pos? vat))
    (-> (bigdec (or netto 0))
        (.multiply (bigdec vat))
        (.divide (bigdec 100) 2 RoundingMode/HALF_UP))
    (bigdec 0)))

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

(defn- podmiot2 [buyer]
  (xml/element ::fa/Podmiot2 {}
    (xml/element ::fa/DaneIdentyfikacyjne {}
      (xml/element ::fa/NIP {} (nip-str (:nip buyer)))
      (xml/element ::fa/Nazwa {} (str (:name buyer))))
    (adres buyer)
    (xml/element ::fa/JST {} "2")
    (xml/element ::fa/GV {} "2")))

(defn- bucket-sum-elements
  "Emit the optional P_13_N/P_14_N groups in the exact order required by the
  schema. Elements are only emitted when the bucket is non-empty."
  [sums]
  (let [{:keys [std red1 red2 taxi zero-kr exempt]} sums]
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
         (xml/element ::fa/P_13_7 {} (amount-str (:netto exempt))))])))

(defn- adnotacje
  "Minimal required Adnotacje block. P_19 flag is set based on whether any
  item is exempt (nil :vat)."
  [items]
  (let [has-exempt? (boolean (some #(nil? (:vat %)) items))
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

(defn- fa-wiersz [idx item]
  (let [netto (.setScale (item-netto item) 2 RoundingMode/HALF_UP)]
    (xml/element ::fa/FaWiersz {}
      (xml/element ::fa/NrWierszaFa {} (str (inc idx)))
      (xml/element ::fa/P_7 {} (str (:title item)))
      (xml/element ::fa/P_8A {} (or (:unit item) "szt."))
      (xml/element ::fa/P_8B {} (qty-str (or (:quantity item) 1)))
      (xml/element ::fa/P_9A {} (amount-str netto))
      (xml/element ::fa/P_11 {} (amount-str netto))
      (xml/element ::fa/P_12 {} (item-vat-code item)))))

(defn- fa-body [{:keys [items number date place]}]
  (let [sums    (bucket-sums items)
        brutto  (total-brutto items)
        bucket-els (bucket-sum-elements sums)]
    (xml/element ::fa/Fa {}
      (concat
        [(xml/element ::fa/KodWaluty {} "PLN")
         (xml/element ::fa/P_1 {} (iso-date date))]
        (when place [(xml/element ::fa/P_1M {} place)])
        [(xml/element ::fa/P_2 {} (str number))
         (xml/element ::fa/P_6 {} (iso-date date))]
        (mapcat (fn [x] (if (sequential? x) x [x])) bucket-els)
        [(xml/element ::fa/P_15 {} (amount-str brutto))
         (adnotacje items)
         (xml/element ::fa/RodzajFaktury {} "VAT")]
        (map-indexed fa-wiersz items)))))

;; ---------------- public API ----------------

(defn invoice->fa3-xml
  "Render an invoice map to an FA(3) XML string.

  Required keys: :seller, :buyer, :items, :number, :date (LocalDate).
  Optional keys: :place (miejsce wystawienia)."
  [{:keys [seller buyer items number date] :as invoice}]
  (let [doc (xml/element ::fa/Faktura {}
              (naglowek)
              (podmiot1 seller)
              (podmiot2 buyer)
              (fa-body invoice))]
    (xml/emit-str doc)))
