(ns invoices.pdf
  (:require [clj-pdf.core :refer [pdf]]
            [invoices.time :refer [skip-days-off]]
            [invoices.calc :refer [brutto vat round]]
            [clojure.string :as str])
  (:import [java.awt Font]))

(defn total [items getter]
  (->> items (map getter) (reduce +) round str))

(defn format-total [items getter]
  [:cell {:background-color [216 247 249]} (total items getter)])

(defn format-param [param] [:cell.param {:align :right} (str param ":  ")])
(defn format-value [value] [:cell {:colspan 2} (str value)])

(defn vat-label
  "Display label for a stawka VAT column. Integers render as `N%`, keyword
  classifications render with their conventional Polish label. nil means
  legacy-exempt (zw.)."
  [vat-level]
  (cond
    (nil? vat-level)    "zw."
    (= :zw vat-level)   "zw."
    (= :np vat-level)   "np I"
    (= :np-eu vat-level) "np II"
    :else (str vat-level "%")))

(defn vat-sort-key
  "Total ordering over the heterogeneous `:vat` domain used for summary
  rows: nil first (matching the legacy `(sort [8 nil])` → `(nil 8)`
  order), then numeric rates ascending, then keyword classifications
  in a stable canonical order. Mixed `:vat` across keywords and
  integers would otherwise crash `sort` because Clojure's default
  comparator can't compare Long to Keyword."
  [vat-level]
  (cond
    (nil? vat-level)     [0]
    (number? vat-level)  [1 vat-level]
    (= :zw vat-level)    [2 0]
    (= :np vat-level)    [2 1]
    (= :np-eu vat-level) [2 2]
    :else                [2 3 (str vat-level)]))

(defn format-product [{netto :netto vat-level :vat title :title :as item}]
  (concat
   [[:cell {:colspan 4} title]]
   (map str [1
             (-> netto round str)
             (vat-label vat-level)
             (-> item vat round str)
             (brutto item)])))

(defn format-summary
  ([items vat-level] (format-summary (filter #(= (:vat %) vat-level) items) vat-level ""))
  ([items vat-level text]
   [[:cell {:background-color [84 219 229] :set-border [] :colspan 5 :align :center} text]
    (format-total items (constantly 1))
    (format-total items :netto)
    (get {:total "wszystkie"} vat-level (vat-label vat-level))
    (format-total items vat)
    (format-total items brutto)
    ]))

(defn format-notes
  "Adds an `Uwagi` section with the given notes, one per line."
  [notes]
  (when (seq notes)
    [[:spacer 2] [:line]
     (concat
      [:table {:border false :padding 0 :spacing 0} [[:phrase {:style :bold} "Uwagi:"]]]
      (map vector notes))]))

;;; Title helpers
(def month-names
  ["" "styczen" "luty" "marzec" "kwiecien" "maj" "czerwiec" "lipiec" "sierpien" "wrzesien" "pazdziernik" "listopad" "grudzien"])

(defn month-name [month]
  (->> (str/split month #"/") second (Integer/parseInt) (nth month-names)))

(defn title-base [{{team :team who :name} :seller title :title}]
  (if title title (->> [team who] (remove nil?) (str/join "_"))))

(defn get-title [item which]
  (->> [(title-base item) (month-name which) which]
       (map #(str/replace % #"[ -/]" "_"))
       (str/join "_")))
;;;

(defn pdf-body
  "Generate the actual pdf body"
  [title seller buyer items when number font]
  [{:title         title
     :right-margin  50
     :author        (:name seller)
     :bottom-margin 10
     :left-margin   10
     :top-margin    20
     :font font
     :size          "a4"
     :footer        "page"}

    [:heading "Faktura"]
    [:spacer]
    [:paragraph (str "Nr " number)]
    [:spacer 2]

    [:table {:border false :padding 0 :spacing 0 :num-cols 6}
     [(format-param "sprzedawca") (format-value (:name seller)) (format-param "nabywca") (format-value (:name buyer))]
     [(format-param "adres") (format-value (:address seller)) (format-param "adres") (format-value (:address buyer))]
     [(format-param "nip") (format-value (:nip seller)) (format-param "nip") (format-value (:nip buyer))]
     (clojure.core/when (:phone seller) [(format-param "numer telefonu") (format-value (:phone seller))])]

    [:spacer]
    [:line]
    [:table {:border false :padding 0 :spacing 0 :num-cols 6}
     (concat [(format-param "data wystawienia") (-> when .toString format-value)]
             (if-not (:account seller) ["" "" ""]
               [(format-param "sposób płatności") (format-value "Przelew")]))
     (concat [(format-param "data sprzedaży") (-> when .toString format-value)]
             (if-not (:account seller) ["" "" ""]
                     [(format-param "bank") (format-value (:bank seller))]))
     (concat [(format-param "termin płatności") (-> when (.plusDays 14) .toString format-value)]
             (if-not (:account seller) ["" "" ""]
                     [(format-param "numer konta") (format-value (:account seller))]))]
    (concat
     [:table
      {:header [{:background-color [216 247 249]} "Lp." [:cell {:colspan 4} "Nazwa"] "Ilość" "Cena netto" "Stawka VAT" "Kwota VAT" "Wartość brutto"]
       :num-cols 10}]
     (->> items
          (map format-product)
          (map-indexed #(concat [(inc %1)] %2)))
     [(format-summary items :total (str "Razem do zapłaty: " (total items brutto) " PLN"))]
     (->> items (map :vat) distinct (sort-by vat-sort-key)
          (map (partial format-summary items))))

   [:table {:border false :padding 0 :spacing 0 :num-cols 6}
    [(format-param "Zapłacono") (format-value "0.00 PLN")
     (format-param "Do zapłaty") (format-value (str (total items brutto) " PLN"))]]

   ])

(def ^:private np-pdf-note
  "Usługa nie podlega opodatkowaniu VAT na terytorium RP — miejsce świadczenia ustalone zgodnie z art. 28b ustawy z dnia 11 marca 2004 r. o podatku od towarów i usług.")

(def ^:private np-eu-pdf-note
  "Świadczenie usług, o których mowa w art. 100 ust. 1 pkt 4 ustawy z dnia 11 marca 2004 r. o podatku od towarów i usług — miejsce świadczenia poza terytorium RP, zgodnie z art. 28b.")

(defn- np-auto-notes
  "For invoices that include np / np-eu items, automatically prepend the
  legal-basis citation to the Uwagi block. The user must not have to
  remember to add it manually — the tax classification is visible in
  the `:vat` key and the note follows from it."
  [items]
  (let [vats (set (map :vat items))]
    (cond-> []
      (contains? vats :np)    (conj np-pdf-note)
      (contains? vats :np-eu) (conj np-eu-pdf-note))))

(defn add-notes
  "Some items require extra notes to be added (for various legal reasons).
  For np/np-eu classifications the citation is generated automatically
  from the `:vat` keyword — the user should not have to copy-paste it."
  [body items]
  (let [explicit (->> items (map :notes) (remove nil?) flatten)
        auto (np-auto-notes items)
        all-notes (distinct (concat auto explicit))]
    (conj body (format-notes all-notes))))


(defn render [{seller :seller buyer :buyer items :items font-path :font-path :as invoice} when number]
  (let [title (get-title invoice number)]
    (println " - rendering" title)
    (-> title
        (pdf-body seller buyer items when number (clojure.core/when font-path {:encoding :unicode :ttf-name font-path}))
        (add-notes items)
        (pdf (str title ".pdf")))
    (-> title (str ".pdf") java.io.File.)))
