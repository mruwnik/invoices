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

(defn format-product [{netto :netto vat-level :vat title :title :as item}]
  (concat
   [[:cell {:colspan 4} title]]
   (map str [1
             (-> netto round str)
             (if-not vat-level "zw." (str vat-level "%"))
             (-> item vat round str)
             (brutto item)])))

(defn format-summary
  ([items vat-level] (format-summary (filter #(= (:vat %) vat-level) items) vat-level ""))
  ([items vat-level text]
   [[:cell {:background-color [84 219 229] :set-border [] :colspan 5 :align :center} text]
    (format-total items (constantly 1))
    (format-total items :netto)
    ({:total "wszystkie" nil "zw."} vat-level (str vat-level "%"))
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
     (->> items (map :vat) distinct sort (map (partial format-summary items))))

   [:table {:border false :padding 0 :spacing 0 :num-cols 6}
    [(format-param "Zapłacono") (format-value "0.00 PLN")
     (format-param "Do zapłaty") (format-value (str (total items brutto) " PLN"))]]

   ])

(defn add-notes
  "Some items require extra notes to be added (for various legal reasons)"
  [body items]
  (->> items (map :notes) (remove nil?) flatten distinct format-notes (conj body)))


(defn render [{seller :seller buyer :buyer items :items font-path :font-path :as invoice} when number]
  (let [title (get-title invoice number)]
    (println " - rendering" title)
    (-> title
        (pdf-body seller buyer items when number (clojure.core/when font-path {:encoding :unicode :ttf-name font-path}))
        (add-notes items)
        (pdf (str title ".pdf")))
    (-> title (str ".pdf") java.io.File.)))
