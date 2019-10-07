(ns invoices.pdf-test
  (:require [clojure.test :refer :all]
            [invoices.pdf :refer :all]))


(deftest test-format-total
  (testing "Check whether the `total` cell gets correctly formatted"
    (is (= (format-total [1 2 3 4] identity) [:cell {:background-color [216 247 249]} "10.0"])))

  (testing "Check whether the `total` cell gets correctly formatted with accessor"
    (is (= (format-total [] :netto) [:cell {:background-color [216 247 249]} "0.0"]))
    (is (= (format-total [{:netto 12} {:netto 32}] :netto)
           [:cell {:background-color [216 247 249]} "44.0"]))))

(deftest test-format-param
  (testing "Check whether parameters get correctly formatted"
    (is (= (format-param 123) [:cell.param {:align :right} "123:  "]))
    (is (= (format-param "bla") [:cell.param {:align :right} "bla:  "]))))

(deftest test-format-value
  (testing "Check whether values get correctly formatted"
    (is (= (format-value 123) [:cell {:colspan 2} "123"]))
    (is (= (format-value "bla") [:cell {:colspan 2} "bla"]))))

(deftest test-format-product
  (testing "Check whether whole products get correctly formatted"
    (is (= (format-product {:netto 1000 :vat 23 :title "bla bla"})
          [[:cell {:colspan 4} "bla bla"] "1" "1000.0" "23%" "230.0" "1230.0"]))
    (is (= (format-product {:netto 1000 :vat 0 :title "bla bla"})
         [[:cell {:colspan 4} "bla bla"] "1" "1000.0" "0%" "0.0" "1000.0"])))

  (testing "Check whether no vat is handled correctly"
    (is (= (format-product {:netto 1000 :title "bla bla"})
           [[:cell {:colspan 4} "bla bla"] "1" "1000.0" "zw." "0.0" "1000.0"]))))

(deftest test-get-title
  (testing "Check whether getting titles works"
    (is (= (get-title nil "mr blobby" "2019/02/11") "mr_blobby_luty_2019_02_11"))
    (is (= (get-title "asd" "mr blobby" "2019/02/11") "asd_mr_blobby_luty_2019_02_11"))))

(deftest test-get-pdf
  (testing "Check whether generating pdf bodies works"
    (let [seller {:name "Mr. Blobby"
                  :address "ul. podwodna, 12-345, Mierzów"
                  :nip 1234567890
                  :phone 876543216
                  :account "65 2345 1233 1233 4322 3211 4567"
                  :bank "Skok hop"}
          buyer {:name "Buty S.A."
                 :address "ul. Szewska 32, 76-543, Bąków"
                 :nip 9875645342}
          items [{:vat 8 :netto 123.21 :title "Buty kowbojskie"}
                 {:netto 321.45 :title "Usługa szewska bez VAT"}]
          date (java.time.LocalDate/parse "2018-03-02")
          font "/usr/share/fonts/truetype/freefont/FreeSans.ttf"]
    (is (= (pdf-body "pdf title" seller buyer items date 12 font)
           [{:bottom-margin 10
             :right-margin 50
             :left-margin 10
             :footer "page"
             :font "/usr/share/fonts/truetype/freefont/FreeSans.ttf"
             :size "a4"
             :title "pdf title"
             :author "Mr. Blobby"
             :top-margin 20}
            [:heading "Faktura"]
            [:spacer]
            [:paragraph "Nr 12"]
            [:spacer 2]
            [:table {:border false, :padding 0, :spacing 0, :num-cols 6}
             [[:cell.param {:align :right} "sprzedawca:  "]
              [:cell {:colspan 2} "Mr. Blobby"]
              [:cell.param {:align :right} "nabywca:  "]
              [:cell {:colspan 2} "Buty S.A."]]
             [[:cell.param {:align :right} "adres:  "]
              [:cell {:colspan 2} "ul. podwodna, 12-345, Mierzów"]
              [:cell.param {:align :right} "adres:  "]
              [:cell {:colspan 2} "ul. Szewska 32, 76-543, Bąków"]]
             [[:cell.param {:align :right} "nip:  "]
              [:cell {:colspan 2} "1234567890"]
              [:cell.param {:align :right} "nip:  "]
              [:cell {:colspan 2} "9875645342"]]
             [[:cell.param {:align :right} "numer telefonu:  "]
              [:cell {:colspan 2} "876543216"]]]
            [:spacer]
            [:line]
            [:table {:border false, :padding 0, :spacing 0, :num-cols 6}
             [[:cell.param {:align :right} "data wystawienia:  "]
              [:cell {:colspan 2} "2018-03-02"]
              [:cell.param {:align :right} "sposób płatności:  "]
              [:cell {:colspan 2} "Przelew"]]
             [[:cell.param {:align :right} "data sprzedaży:  "]
              [:cell {:colspan 2} "2018-03-02"]
              [:cell.param {:align :right} "bank:  "]
              [:cell {:colspan 2} "Skok hop"]]
             [[:cell.param {:align :right} "termin płatności:  "]
              [:cell {:colspan 2} "2018-03-16"]
              [:cell.param {:align :right} "numer konta:  "]
              [:cell {:colspan 2} "65 2345 1233 1233 4322 3211 4567"]]]
            (list :table {:header [{:background-color [216 247 249]} "Lp." [:cell {:colspan 4} "Nazwa"] "Ilość" "Cena netto" "Stawka VAT" "Kwota VAT" "Wartość brutto"], :num-cols 10}
                     (list 1 [:cell {:colspan 4} "Buty kowbojskie"] "1" "123.21" "8%" "9.86" "133.07")
                     (list 2 [:cell {:colspan 4} "Usługa szewska bez VAT"] "1" "321.45" "zw." "0.0" "321.45")
                     [[:cell {:background-color [84 219 229], :colspan 5, :align :center} "Razem"]
                      [:cell {:background-color [216 247 249]} "2.0"]
                      [:cell {:background-color [216 247 249]} "444.66"]
                      ""
                      [:cell {:background-color [216 247 249]} "9.86"]
                      [:cell {:background-color [216 247 249]} "454.52"]])])))))
