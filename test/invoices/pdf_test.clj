(ns invoices.pdf-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
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

(deftest test-format-notes
  (testing "Check whether notes get correctly formatted"
    (is (= (format-notes ["line 1" "line 2" "line 3"])
           [[:spacer 2] [:line] (list :table {:border false, :padding 0, :spacing 0} [[:phrase {:style :bold} "Uwagi:"]] ["line 1"] ["line 2"] ["line 3"])])))

  (testing "Check whether the notes section is skipped if none provided."
    (is (nil? (format-notes [])))
    (is (nil? (format-notes nil)))))

(deftest test-month-name
  (testing "Check whether the month names are correctly returned"
    (doseq [[i name] (rest (map-indexed vector month-names))]
      (is (= (month-name (str "2012/" i "/02")) name)))))

(deftest test-title-base
  (testing "Check that :title is used if available"
    (is (= (title-base {:seller {:team "A" :name "bla"} :title "title"}) "title"))
    (is (= (title-base {:title "title"}) "title")))
  (testing "Check that the title is made from the team and name"
    (is (= (title-base {:seller {:team "A" :name "bla"}}) "A_bla"))
    (is (= (title-base {:seller {:name "bla"}}) "bla"))
    (is (= (title-base {:seller {:team "A"}}) "A"))
    (is (= (title-base {:seller {}}) ""))))

(deftest test-get-title
  (testing "Check whether getting titles works"
    (is (= (get-title {:title "mr blobby"} "2019/02/11") "mr_blobby_luty_2019_02_11"))
    (is (= (get-title {:seller {:team "asd" :name "mr blobby"}} "2019/02/11") "asd_mr_blobby_luty_2019_02_11"))))

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
                     [[:cell {:background-color [84 219 229], :set-border [], :colspan 5, :align :center} "Razem do zapłaty: 454.52 PLN"]
                      [:cell {:background-color [216 247 249]} "2.0"]
                      [:cell {:background-color [216 247 249]} "444.66"]
                      "wszystkie"
                      [:cell {:background-color [216 247 249]} "9.86"]
                      [:cell {:background-color [216 247 249]} "454.52"]]
                     [[:cell {:background-color [84 219 229], :set-border [], :colspan 5, :align :center} ""]
                      [:cell {:background-color [216 247 249]} "1.0"]
                      [:cell {:background-color [216 247 249]} "321.45"]
                      "zw."
                      [:cell {:background-color [216 247 249]} "0.0"]
                      [:cell {:background-color [216 247 249]} "321.45"]]
                     [[:cell {:background-color [84 219 229], :set-border [], :colspan 5, :align :center} ""]
                      [:cell {:background-color [216 247 249]} "1.0"]
                      [:cell {:background-color [216 247 249]} "123.21"]
                      "8%"
                      [:cell {:background-color [216 247 249]} "9.86"]
                      [:cell {:background-color [216 247 249]} "133.07"]])
            [:table {:border false, :padding 0, :spacing 0, :num-cols 6}
             [[:cell.param {:align :right} "Zapłacono:  "]
              [:cell {:colspan 2} "0.00 PLN"]
              [:cell.param {:align :right} "Do zapłaty:  "]
              [:cell {:colspan 2} "454.52 PLN"]]]])))))

(deftest test-add-notes
  (testing "Check whether notes get correctly added"
    (is (= (add-notes [:table] [{:notes ["line 1" "line 2"]} {} {} {:notes ["line 3"]} {:notes []}])
           [:table
            [[:spacer 2] [:line]
             (list :table {:border false, :padding 0, :spacing 0} [[:phrase {:style :bold} "Uwagi:"]]
                   ["line 1"] ["line 2"] ["line 3"])]])))

  (testing "Check whether duplicates get removed"
    (is (= (add-notes [:table] [{:notes ["line 1" "line 1"]} {} {} {:notes ["line 1"]} {:notes []}])
           [:table
            [[:spacer 2] [:line]
             (list :table {:border false, :padding 0, :spacing 0} [[:phrase {:style :bold} "Uwagi:"]] ["line 1"])]])))

  (testing "Check whether the notes section is skipped if none provided."
    (is (= (add-notes [:table] []) [:table nil]))
    (is (= (add-notes [:table] nil) [:table nil]))))

(deftest vat-label-for-np-classifications
  (testing "Stawka VAT label for each :vat value"
    (is (= "23%" (vat-label 23)))
    (is (= "8%"  (vat-label 8)))
    (is (= "0%"  (vat-label 0)))
    (is (= "zw." (vat-label nil)))
    (is (= "zw." (vat-label :zw)))
    (is (= "np I"  (vat-label :np)))
    (is (= "np II" (vat-label :np-eu)))))

(deftest format-product-for-np-items
  (testing "np item renders with 'np I' in stawka column and 0 VAT amount"
    (is (= '([:cell {:colspan 4} "Software development"]
             "1" "10000.0" "np I" "0.0" "10000.0")
           (format-product {:netto 10000 :vat :np :title "Software development"}))))
  (testing "np-eu item renders 'np II'"
    (is (= '([:cell {:colspan 4} "Consulting"]
             "1" "5000.0" "np II" "0.0" "5000.0")
           (format-product {:netto 5000 :vat :np-eu :title "Consulting"}))))
  (testing ":zw keyword renders 'zw.' (matches legacy nil behavior)"
    (let [formatted (vec (format-product {:netto 1000 :vat :zw :title "Z"}))]
      (is (= "zw." (nth formatted 3))))))

(deftest add-notes-auto-prepends-np-legal-basis
  (testing ":vat :np item triggers automatic art. 28b legal-basis note"
    (let [result (add-notes [:table] [{:vat :np :netto 100 :title "x"}])
          rendered (str result)]
      (is (str/includes? rendered "art. 28b")
          "Automatic np note must cite art. 28b")
      (is (str/includes? rendered "nie podlega opodatkowaniu VAT")
          "Automatic np note must mention nie podlega opodatkowaniu")))
  (testing ":vat :np-eu item triggers automatic art. 100 ust. 1 pkt 4 note"
    (let [result (add-notes [:table] [{:vat :np-eu :netto 100 :title "x"}])
          rendered (str result)]
      (is (str/includes? rendered "art. 100 ust. 1 pkt 4"))))
  (testing "Pure-VAT invoice does not emit legal-basis note"
    (let [result (add-notes [:table] [{:vat 23 :netto 100 :title "x"}])
          rendered (str result)]
      (is (not (str/includes? rendered "art. 28b")))
      (is (not (str/includes? rendered "art. 100")))))
  (testing "User explicit :notes still render alongside the auto note"
    (let [result (add-notes [:table] [{:vat :np :netto 100 :title "x"
                                        :notes ["custom line"]}])
          rendered (str result)]
      (is (str/includes? rendered "art. 28b"))
      (is (str/includes? rendered "custom line")))))

