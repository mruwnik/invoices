(ns invoices.pdf
  (:require [clj-pdf.core :refer [pdf]]
            [invoices.time :refer [skip-days-off last-working-day]]
            [invoices.calc :refer [brutto vat round]]
            [clojure.string :as str])
  (:import [java.awt Font]))

(defn format-total [items getter]
  [:cell {:background-color [216 247 249]}
   (->> items (map getter) (reduce +) round str)])

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


(defn get-title [team who which]
  (let [[nr month year] (-> which (str/split #"/"))
        months ["" "styczen" "luty" "marzec" "kwiecien" "maj" "czerwiec" "lipiec"
                "sierpien" "wrzesien" "pazdziernik" "listopad" "grudzien"]]
    (-> (str/join "_" (remove nil? [team who (nth months (Integer/parseInt month)) which]))
        (str/replace #"[ -/]" "_"))))


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
    [(format-param "data wystawienia") (-> when .toString format-value) (format-param "sposób płatności") (format-value "Przelew")]
    [(format-param "data sprzedaży") (-> when .toString format-value) (format-param "bank") (format-value (:bank seller))]
    [(format-param "termin płatności") (-> when (.plusDays 14) .toString format-value) (format-param "numer konta") (format-value (:account seller))]]

   (concat
    [:table
     {:header [{:background-color [216 247 249]} "Lp." [:cell {:colspan 4} "Nazwa"] "Ilość" "Cena netto" "Stawka VAT" "Kwota VAT" "Wartość brutto"]
      :num-cols 10}]
    (->> items
         (map format-product)
         (map-indexed #(concat [(inc %1)] %2)))
    [[[:cell {:background-color [84 219 229] :colspan 5 :align :center} "Razem"]
      (format-total items (constantly 1))
      (format-total items :netto)
      ""
      (format-total items vat)
      (format-total items brutto)]])])


(defn render [seller buyer items when number & [font-path]]
  (let [title (get-title (:team seller) (:name seller) number)]
    (println " -" title)
    (pdf (pdf-body title seller buyer items when number (clojure.core/when font-path{:encoding :unicode :ttf-name font-path}))
         (str title ".pdf"))
    title))
