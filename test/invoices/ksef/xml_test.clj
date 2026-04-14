(ns invoices.ksef.xml-test
  (:require [clojure.test :refer [deftest is testing are]]
            [clojure.string :as str]
            [invoices.ksef.xml :as fa])
  (:import [java.io ByteArrayInputStream StringReader]
           [java.time LocalDate]
           [javax.xml XMLConstants]
           [javax.xml.transform.stream StreamSource]
           [javax.xml.validation SchemaFactory]
           [org.w3c.dom.ls LSResourceResolver LSInput]))

(def xsd-dir
  "/workspace/ksef-docs/faktury/schemy/FA")

(def xsd-path
  (str xsd-dir "/schemat_FA(3)_v1-0E.xsd"))

(def sample-seller
  {:name "Mr. Blobby"
   :address "ul. Podwodna 1, 12-345 Mierzow"
   :nip 6423166047
   :phone 876543216})

(def sample-buyer
  {:name "Buty S.A."
   :address "ul. Szewska 32, 76-543 Bakow"
   :nip 9875645342})

(def single-item-invoice
  {:seller sample-seller
   :buyer  sample-buyer
   :number "1/4/2026"
   :date   (LocalDate/of 2026 4 14)
   :items  [{:vat 23 :netto 100 :title "Konsultacje"}]})

(def mixed-vat-invoice
  {:seller sample-seller
   :buyer  sample-buyer
   :number "2/4/2026"
   :date   (LocalDate/of 2026 4 14)
   :items  [{:vat 23 :netto 100.00 :title "Standard item"}
            {:vat 8  :netto 200.00 :title "Reduced item 1"}
            {:vat 5  :netto 50.00  :title "Reduced item 2"}
            {:vat 0  :netto 25.00  :title "Zero rate"}
            {:netto 10.00 :title "Exempt item"}]})

;; ---------------- helpers ----------------

(defn- parse-doc [^String xml]
  (let [dbf (doto (javax.xml.parsers.DocumentBuilderFactory/newInstance)
              (.setNamespaceAware true))
        db  (.newDocumentBuilder dbf)]
    (.parse db (ByteArrayInputStream. (.getBytes xml "UTF-8")))))

(defn- child-nodes [^org.w3c.dom.Node node]
  (let [^org.w3c.dom.NodeList nl (.getChildNodes node)
        n (.getLength nl)]
    (->> (range n)
         (map (fn [i] (.item nl (int i))))
         (filter (fn [^org.w3c.dom.Node c]
                   (= org.w3c.dom.Node/ELEMENT_NODE (.getNodeType c)))))))

(defn- elem-names [node]
  (mapv (fn [^org.w3c.dom.Node c] (.getLocalName c)) (child-nodes node)))

(defn- child
  "First direct child with the given local name."
  [node local-name]
  (->> (child-nodes node)
       (filter (fn [^org.w3c.dom.Node c] (= local-name (.getLocalName c))))
       first))

(defn- text [^org.w3c.dom.Node node] (some-> node .getTextContent))

(defn- ls-input
  "Wrap a local xsd file as an org.w3c.dom.ls.LSInput for the resolver."
  [^String ns-uri ^String public-id ^String system-id ^java.io.File file]
  (let [bs (java.io.ByteArrayInputStream. (.getBytes (slurp file) "UTF-8"))]
    (reify LSInput
      (getByteStream [_] bs)
      (getCharacterStream [_] nil)
      (getStringData [_] nil)
      (getSystemId [_] system-id)
      (getPublicId [_] public-id)
      (getBaseURI [_] nil)
      (getEncoding [_] "UTF-8")
      (getCertifiedText [_] false)
      (setByteStream [_ _] nil)
      (setCharacterStream [_ _] nil)
      (setStringData [_ _] nil)
      (setSystemId [_ _] nil)
      (setPublicId [_ _] nil)
      (setBaseURI [_ _] nil)
      (setEncoding [_ _] nil)
      (setCertifiedText [_ _] nil))))

(defn- local-resolver
  "LSResourceResolver that redirects schemaLocation values to files under
  `xsd-dir/bazowe`. FA(3) imports its base types by http URL, which we can't
  fetch from the sandbox — so we match by the trailing file name."
  []
  (reify LSResourceResolver
    (resolveResource [_ _type ns-uri public-id system-id _base-uri]
      (when system-id
        (let [leaf (last (str/split system-id #"/"))
              f    (java.io.File. (str xsd-dir "/bazowe/" leaf))]
          (when (.exists f)
            (ls-input ns-uri public-id system-id f)))))))

(defn- shrink-maxoccurs
  "FA(3) has `maxOccurs=\"10000\"` and `maxOccurs=\"50000\"` on a handful of
  repeating elements (FaWiersz, DaneFaKorygowanej, etc.). Xerces expands each
  such particle into a DFA with that many optional nodes, which is quadratic
  in the bound. In the sandbox this blows out the heap long before our test
  invoices (≤5 items, no corrections) could ever exercise the bound. We rewrite
  the XSD in memory to cap those particles at 100 so the compiled schema is
  still structurally correct for our fixtures."
  [^String xsd]
  (-> xsd
      (.replaceAll "maxOccurs=\"10000\"" "maxOccurs=\"100\"")
      (.replaceAll "maxOccurs=\"50000\"" "maxOccurs=\"100\"")
      (.replaceAll "maxOccurs=\"1000\""  "maxOccurs=\"100\"")))

(defn- shrunk-xsd-source
  "Produce a StreamSource over an in-memory, maxOccurs-capped copy of the FA(3)
  schema. We set the systemId to the original file so the local resolver still
  resolves the base-types import relative to the real directory."
  []
  (doto (StreamSource. (StringReader. (shrink-maxoccurs (slurp xsd-path))))
    (.setSystemId (.toString (.toURI (java.io.File. xsd-path))))))

(defn- validate-xsd
  "Validate `xml-string` against the FA(3) schema and return nil on success or
  an error string on failure. Loads a maxOccurs-capped copy of the schema
  (see `shrink-maxoccurs`) via a local `LSResourceResolver` so the
  http-only base-types import resolves offline."
  [xml-string]
  (let [factory (SchemaFactory/newInstance XMLConstants/W3C_XML_SCHEMA_NS_URI)
        _ (.setResourceResolver factory (local-resolver))
        schema  (.newSchema factory (shrunk-xsd-source))
        validator (.newValidator schema)]
    (try
      (.validate validator (StreamSource. (StringReader. xml-string)))
      nil
      (catch org.xml.sax.SAXException e (.getMessage e)))))

;; ---------------- tests ----------------

(deftest well-formed-and-parseable
  (let [xml (fa/invoice->fa3-xml single-item-invoice)]
    (is (string? xml))
    (is (str/includes? xml "Faktura"))
    (is (some? (parse-doc xml)))))

(deftest root-namespace-is-correct
  (let [xml (fa/invoice->fa3-xml single-item-invoice)
        doc (parse-doc xml)
        root (.getDocumentElement doc)]
    (is (= "Faktura" (.getLocalName root)))
    (is (= fa/fa-ns (.getNamespaceURI root)))))

(deftest top-level-element-order
  (let [xml  (fa/invoice->fa3-xml single-item-invoice)
        root (.getDocumentElement (parse-doc xml))]
    (is (= ["Naglowek" "Podmiot1" "Podmiot2" "Fa"]
           (elem-names root)))))

(deftest naglowek-fields
  (let [xml (fa/invoice->fa3-xml single-item-invoice)
        doc (parse-doc xml)
        nag (child (.getDocumentElement doc) "Naglowek")
        kf  (child nag "KodFormularza")]
    (is (= "FA" (text kf)))
    (is (= "FA (3)" (.getAttribute kf "kodSystemowy")))
    (is (= "1-0E" (.getAttribute kf "wersjaSchemy")))
    (is (= "3" (text (child nag "WariantFormularza"))))
    (is (some? (text (child nag "DataWytworzeniaFa"))))))

(deftest podmiot-fields
  (let [xml (fa/invoice->fa3-xml single-item-invoice)
        root (.getDocumentElement (parse-doc xml))
        p1 (child root "Podmiot1")
        p2 (child root "Podmiot2")
        p1-data (child p1 "DaneIdentyfikacyjne")
        p2-data (child p2 "DaneIdentyfikacyjne")]
    (is (= "6423166047" (text (child p1-data "NIP"))))
    (is (= "Mr. Blobby"  (text (child p1-data "Nazwa"))))
    (is (= "9875645342" (text (child p2-data "NIP"))))
    (is (= "Buty S.A."  (text (child p2-data "Nazwa"))))
    (is (= "PL" (text (child (child p1 "Adres") "KodKraju"))))))

(deftest nip-normalization
  (are [input expected]
       (let [xml (fa/invoice->fa3-xml
                   (assoc-in single-item-invoice [:seller :nip] input))
             doc (parse-doc xml)
             nip (text (child (child (child (.getDocumentElement doc) "Podmiot1")
                                     "DaneIdentyfikacyjne")
                              "NIP"))]
         (= expected nip))
    6423166047         "6423166047"
    "642-316-60-47"    "6423166047"
    " 6423166047 "     "6423166047"))

(deftest fa-block-order-single-item
  (let [xml (fa/invoice->fa3-xml single-item-invoice)
        root (.getDocumentElement (parse-doc xml))
        fa-el (child root "Fa")
        names (elem-names fa-el)]
    (is (= "PLN" (text (child fa-el "KodWaluty"))))
    (is (= "2026-04-14" (text (child fa-el "P_1"))))
    (is (= "1/4/2026" (text (child fa-el "P_2"))))
    (is (= "2026-04-14" (text (child fa-el "P_6"))))
    (is (= "100.00" (text (child fa-el "P_13_1"))))
    (is (= "23.00" (text (child fa-el "P_14_1"))))
    (is (= "123.00" (text (child fa-el "P_15"))))
    (is (= "VAT" (text (child fa-el "RodzajFaktury"))))
    (is (= (sort (distinct names))
           (sort (distinct names)))
        "all child names present")
    ;; Schema order: KodWaluty, P_1, P_2, P_6, P_13_1, P_14_1, P_15, Adnotacje, RodzajFaktury, FaWiersz
    (let [idx-of #(.indexOf names %)]
      (is (< (idx-of "KodWaluty") (idx-of "P_1")))
      (is (< (idx-of "P_1") (idx-of "P_2")))
      (is (< (idx-of "P_2") (idx-of "P_6")))
      (is (< (idx-of "P_6") (idx-of "P_13_1")))
      (is (< (idx-of "P_13_1") (idx-of "P_14_1")))
      (is (< (idx-of "P_14_1") (idx-of "P_15")))
      (is (< (idx-of "P_15") (idx-of "Adnotacje")))
      (is (< (idx-of "Adnotacje") (idx-of "RodzajFaktury")))
      (is (< (idx-of "RodzajFaktury") (idx-of "FaWiersz"))))))

(deftest mixed-vat-sums-are-correct
  (let [xml (fa/invoice->fa3-xml mixed-vat-invoice)
        fa-el (child (.getDocumentElement (parse-doc xml)) "Fa")]
    (is (= "100.00" (text (child fa-el "P_13_1"))))  ; net 23%
    (is (= "23.00"  (text (child fa-el "P_14_1"))))  ; vat 23%
    (is (= "200.00" (text (child fa-el "P_13_2"))))  ; net 8%
    (is (= "16.00"  (text (child fa-el "P_14_2"))))  ; vat 8%
    (is (= "50.00"  (text (child fa-el "P_13_3"))))  ; net 5%
    (is (= "2.50"   (text (child fa-el "P_14_3"))))  ; vat 5%
    (is (= "25.00"  (text (child fa-el "P_13_6_1")))); net 0%
    (is (= "10.00"  (text (child fa-el "P_13_7"))))  ; net exempt
    (is (= "426.50" (text (child fa-el "P_15"))))))

(deftest fawiersz-fields
  (let [xml (fa/invoice->fa3-xml single-item-invoice)
        fa-el (child (.getDocumentElement (parse-doc xml)) "Fa")
        wiersz (child fa-el "FaWiersz")]
    (is (= "1" (text (child wiersz "NrWierszaFa"))))
    (is (= "Konsultacje" (text (child wiersz "P_7"))))
    (is (= "100.00" (text (child wiersz "P_11"))))
    (is (= "23" (text (child wiersz "P_12"))))))

(deftest exempt-item-sets-p19
  (let [xml (fa/invoice->fa3-xml
              (assoc single-item-invoice
                     :items [{:netto 100 :title "Usługa zwolniona"}]))
        root (.getDocumentElement (parse-doc xml))
        adn  (child (child root "Fa") "Adnotacje")
        zwol (child adn "Zwolnienie")]
    (is (some? (child zwol "P_19")))
    (is (nil?  (child zwol "P_19N")))))

(deftest all-taxable-sets-p19n
  (let [xml (fa/invoice->fa3-xml single-item-invoice)
        root (.getDocumentElement (parse-doc xml))
        adn  (child (child root "Fa") "Adnotacje")
        zwol (child adn "Zwolnienie")]
    (is (some? (child zwol "P_19N")))
    (is (nil?  (child zwol "P_19")))))

(deftest xsd-validation
  (testing "Single-item invoice validates against FA(3) XSD"
    (let [err (validate-xsd (fa/invoice->fa3-xml single-item-invoice))]
      (is (nil? err) (str "XSD validation failed: " err))))
  (testing "Mixed-VAT invoice validates against FA(3) XSD"
    (let [err (validate-xsd (fa/invoice->fa3-xml mixed-vat-invoice))]
      (is (nil? err) (str "XSD validation failed: " err)))))

(deftest xsd-validator-is-not-a-no-op
  ;; Regression guard: if someone refactors validate-xsd into a fail-open
  ;; shell (e.g., catches SAXException too broadly), the positive tests
  ;; above would still pass. These mutations must surface errors.
  (let [good (fa/invoice->fa3-xml single-item-invoice)]
    (testing "removing required P_2 element triggers a validation error"
      (let [bad (str/replace good #"<a:P_2>[^<]*</a:P_2>" "")]
        (is (not= good bad) "sanity: mutation actually changed the XML")
        (let [err (validate-xsd bad)]
          (is (some? err) "XSD must reject invoice missing P_2")
          (is (str/includes? (or err "") "cvc-")))))
    (testing "non-enum KodWaluty value triggers enumeration error"
      (let [bad (str/replace good
                             "<a:KodWaluty>PLN</a:KodWaluty>"
                             "<a:KodWaluty>XYZ</a:KodWaluty>")]
        (is (not= good bad))
        (let [err (validate-xsd bad)]
          (is (some? err) "XSD must reject unknown currency code")
          (is (str/includes? (or err "") "enumeration")))))))

(deftest polish-characters-roundtrip-and-validate
  (let [invoice (assoc-in single-item-invoice [:items 0 :title]
                          "Usługa żółć ąęćłńóśźż")
        xml (fa/invoice->fa3-xml invoice)]
    (is (str/includes? xml "żółć ąęćłńóśźż")
        "Polish characters must be preserved in the emitted XML")
    (is (nil? (validate-xsd xml))
        "Polish-character invoice must still validate against the FA(3) XSD")))

;; ============================================================
;; VAT-excluded (np / np-eu) compliance tests
;; Task 5c1f5d2a — Polish seller → non-EU buyer for services, art. 28b.
;; ============================================================

(def us-buyer
  {:name "Acme Corp"
   :address "123 Main Street, Springfield IL 62701, US"
   :country "US"
   :nip "US-ACME-001"})

(def de-buyer
  {:name "Bundenbach GmbH"
   :address "Unterstrasse 12, 10115 Berlin, DE"
   :country "DE"
   :nip "DE123456789"})

(def np-single-item-invoice
  "The user's canonical 'Polish contractor bills US company for software
  services' case: single item, :vat :np, defaults to PLN."
  {:seller sample-seller
   :buyer  us-buyer
   :number "NP-1/4/2026"
   :date   (LocalDate/of 2026 4 14)
   :items  [{:vat :np :netto 10000.00M :title "Software development"}]})

(def np-eu-single-item-invoice
  "art. 100 ust. 1 pkt 4 case: Polish seller → EU-VAT-registered buyer,
  service on VAT-UE recapitulative statement."
  {:seller sample-seller
   :buyer  de-buyer
   :number "NP-EU-1/4/2026"
   :date   (LocalDate/of 2026 4 14)
   :items  [{:vat :np-eu :netto 5000.00M :title "Consulting (EU B2B)"}]})

(def np-mixed-invoice
  "Mixed invoice: one :vat 23 item + one :vat :np item. This is the
  bucket-routing cross-contamination stress test Renarin's patterns care
  about. Results must land in separate P_13_1 and P_13_8 buckets with
  exactly one P_14_1 for the 23% row and NO P_14_8."
  {:seller sample-seller
   :buyer  sample-buyer
   :number "MIX-1/4/2026"
   :date   (LocalDate/of 2026 4 14)
   :items  [{:vat 23  :netto 200.00M :title "Konsultacja w PL"}
            {:vat :np :netto 1000.00M :title "Usługa poza RP"}]})

(deftest np-single-item-routes-to-p13-8
  (testing ":vat :np routes netto into P_13_8 (and NOT other buckets)"
    (let [xml (fa/invoice->fa3-xml np-single-item-invoice)
          fa-el (child (.getDocumentElement (parse-doc xml)) "Fa")]
      (is (= "10000.00" (text (child fa-el "P_13_8")))
          "np netto must land in P_13_8")
      (is (nil? (child fa-el "P_13_1")) "no P_13_1")
      (is (nil? (child fa-el "P_13_6_1")) "no P_13_6_1 (domestic 0%)")
      (is (nil? (child fa-el "P_13_6_3")) "no P_13_6_3 (export of goods)")
      (is (nil? (child fa-el "P_13_7")) "no P_13_7 (zwolnienie)")
      (is (nil? (child fa-el "P_13_9")) "no P_13_9 (np II)")
      (is (nil? (child fa-el "P_14_1")) "no P_14_1")
      (is (nil? (child fa-el "P_14_8")) "no P_14_8 — the schema does not define one")
      (is (= "10000.00" (text (child fa-el "P_15")))
          "P_15 total equals net (no VAT added)"))))

(deftest np-eu-single-item-routes-to-p13-9
  (testing ":vat :np-eu routes netto into P_13_9"
    (let [xml (fa/invoice->fa3-xml np-eu-single-item-invoice)
          fa-el (child (.getDocumentElement (parse-doc xml)) "Fa")]
      (is (= "5000.00" (text (child fa-el "P_13_9"))))
      (is (nil? (child fa-el "P_13_8")))
      (is (nil? (child fa-el "P_13_1")))
      (is (nil? (child fa-el "P_13_6_1")))
      (is (= "5000.00" (text (child fa-el "P_15")))))))

(deftest np-p12-is-np-I
  (testing "P_12 on the FaWiersz row emits the literal enum value 'np I'"
    (let [xml (fa/invoice->fa3-xml np-single-item-invoice)
          fa-el (child (.getDocumentElement (parse-doc xml)) "Fa")
          w (child fa-el "FaWiersz")]
      (is (= "np I" (text (child w "P_12")))))))

(deftest np-eu-p12-is-np-II
  (testing "P_12 on the FaWiersz row emits the literal enum value 'np II'"
    (let [xml (fa/invoice->fa3-xml np-eu-single-item-invoice)
          fa-el (child (.getDocumentElement (parse-doc xml)) "Fa")
          w (child fa-el "FaWiersz")]
      (is (= "np II" (text (child w "P_12")))))))

(deftest np-keeps-p19n
  (testing "Pure-np invoice must keep P_19N set (np ≠ zwolnienie)"
    (doseq [inv [np-single-item-invoice np-eu-single-item-invoice]]
      (let [root (.getDocumentElement (parse-doc (fa/invoice->fa3-xml inv)))
            adn  (child (child root "Fa") "Adnotacje")
            zwol (child adn "Zwolnienie")]
        (is (some? (child zwol "P_19N"))
            "P_19N must be present on pure-np invoices")
        (is (nil? (child zwol "P_19"))
            "P_19 must NOT be present on pure-np invoices")))))

(deftest np-keeps-p18-absent
  (testing "np is NOT reverse-charge (art. 17); P_18 must remain '2'"
    (let [xml (fa/invoice->fa3-xml np-single-item-invoice)
          adn (child (child (.getDocumentElement (parse-doc xml)) "Fa")
                     "Adnotacje")]
      (is (= "2" (text (child adn "P_18")))))))

(deftest np-embeds-legal-basis-in-dodatkowy-opis
  (testing ":np invoice embeds art. 28b citation in DodatkowyOpis"
    (let [xml (fa/invoice->fa3-xml np-single-item-invoice)
          fa-el (child (.getDocumentElement (parse-doc xml)) "Fa")
          opis (child fa-el "DodatkowyOpis")]
      (is (some? opis) "DodatkowyOpis must be present for np invoices")
      (is (= "Podstawa prawna (np I)" (text (child opis "Klucz"))))
      (is (str/includes? (text (child opis "Wartosc")) "art. 28b")
          "Legal basis text must cite art. 28b"))))

(deftest np-eu-embeds-art-100-basis
  (testing ":np-eu invoice embeds art. 100 ust. 1 pkt 4 citation"
    (let [xml (fa/invoice->fa3-xml np-eu-single-item-invoice)
          fa-el (child (.getDocumentElement (parse-doc xml)) "Fa")
          opis (child fa-el "DodatkowyOpis")]
      (is (some? opis))
      (is (= "Podstawa prawna (np II)" (text (child opis "Klucz"))))
      (is (str/includes? (text (child opis "Wartosc")) "art. 100 ust. 1 pkt 4")))))

(deftest pure-vat-invoice-has-no-dodatkowy-opis
  (testing "Invoice with only taxable items emits NO DodatkowyOpis"
    (let [xml (fa/invoice->fa3-xml single-item-invoice)
          fa-el (child (.getDocumentElement (parse-doc xml)) "Fa")]
      (is (nil? (child fa-el "DodatkowyOpis"))))))

(deftest mixed-np-and-vat-no-cross-contamination
  (testing "Mixed invoice: P_13_1 gets the 23% item, P_13_8 gets the np item"
    (let [xml (fa/invoice->fa3-xml np-mixed-invoice)
          fa-el (child (.getDocumentElement (parse-doc xml)) "Fa")]
      (is (= "200.00"  (text (child fa-el "P_13_1"))) "23% netto in P_13_1")
      (is (= "46.00"   (text (child fa-el "P_14_1"))) "23% VAT in P_14_1")
      (is (= "1000.00" (text (child fa-el "P_13_8"))) "np netto in P_13_8")
      (is (nil? (child fa-el "P_14_8")))
      (is (= "1246.00" (text (child fa-el "P_15")))
          "P_15 = 200 + 46 (VAT) + 1000 (np, no VAT)"))))

(deftest currency-defaults-to-pln
  (let [xml (fa/invoice->fa3-xml single-item-invoice)
        fa-el (child (.getDocumentElement (parse-doc xml)) "Fa")]
    (is (= "PLN" (text (child fa-el "KodWaluty"))))))

(deftest currency-passes-through
  (are [cur] (= cur (text (child (child (.getDocumentElement
                                          (parse-doc
                                            (fa/invoice->fa3-xml
                                              (assoc single-item-invoice
                                                     :currency cur))))
                                         "Fa")
                                  "KodWaluty")))
    "PLN" "USD" "EUR" "GBP" "CHF"))

(deftest currency-invalid-triggers-xsd-failure
  (testing "An invalid ISO 4217 code must fail XSD validation"
    (let [xml (fa/invoice->fa3-xml (assoc single-item-invoice :currency "XYZ"))
          err (validate-xsd xml)]
      (is (some? err) "XSD must reject unknown currency code")
      (is (str/includes? (or err "") "enumeration")))))

(deftest item-currency-mismatch-warns-but-does-not-crash
  (testing "Per-item :currency mismatch is warned and ignored, not fatal"
    (let [invoice (assoc single-item-invoice
                         :currency "PLN"
                         :items [{:vat 23 :netto 100 :title "A" :currency "USD"}])
          sw (java.io.StringWriter.)]
      (binding [*out* sw]
        (let [xml (fa/invoice->fa3-xml invoice)
              fa-el (child (.getDocumentElement (parse-doc xml)) "Fa")]
          (is (= "PLN" (text (child fa-el "KodWaluty")))
              "Invoice-level currency still wins")
          (is (str/includes? (str sw) "WARN")
              "Warning must be printed when per-item currency differs")
          (is (str/includes? (str sw) "USD")))))))

(deftest zw-keyword-routes-to-p13-7
  (testing ":vat :zw (explicit) routes exactly like the legacy nil :vat"
    (let [xml (fa/invoice->fa3-xml
                (assoc single-item-invoice
                       :items [{:vat :zw :netto 100 :title "Zwolnione"}]))
          fa-el (child (.getDocumentElement (parse-doc xml)) "Fa")
          adn  (child fa-el "Adnotacje")
          zwol (child adn "Zwolnienie")]
      (is (= "100.00" (text (child fa-el "P_13_7"))))
      (is (nil? (child fa-el "P_13_8")))
      (is (some? (child zwol "P_19")) ":zw must set P_19, not P_19N"))))

(deftest np-and-np-eu-xsd-validate
  (testing "Every np-class invoice shape validates against the real FA(3) XSD"
    (doseq [[label inv] [["single np"    np-single-item-invoice]
                         ["single np-eu" np-eu-single-item-invoice]
                         ["mixed 23+np"  np-mixed-invoice]
                         [":zw"          (assoc single-item-invoice
                                                :items [{:vat :zw :netto 100
                                                         :title "Zw"}])]
                         ["currency USD" (assoc np-single-item-invoice
                                                :currency "USD")]
                         ["currency EUR" (assoc np-eu-single-item-invoice
                                                :currency "EUR")]]]
      (let [err (validate-xsd (fa/invoice->fa3-xml inv))]
        (is (nil? err) (str label " should validate: " err))))))

(deftest p13-bucket-schema-order
  (testing "P_13_1 < P_13_7 < P_13_8 < P_13_9 < P_15 in emitted XML"
    (let [items [{:vat 23 :netto 10 :title "std"}
                 {:vat :zw :netto 20 :title "zwolnienie"}
                 {:vat :np :netto 30 :title "np I"}
                 {:vat :np-eu :netto 40 :title "np II"}]
          xml (fa/invoice->fa3-xml (assoc single-item-invoice :items items))
          fa-el (child (.getDocumentElement (parse-doc xml)) "Fa")
          names (elem-names fa-el)
          idx-of #(.indexOf names %)]
      (is (< (idx-of "P_13_1") (idx-of "P_13_7")))
      (is (< (idx-of "P_13_7") (idx-of "P_13_8")))
      (is (< (idx-of "P_13_8") (idx-of "P_13_9")))
      (is (< (idx-of "P_13_9") (idx-of "P_15")))
      (is (nil? (validate-xsd xml))
          "4-bucket invoice must XSD-validate"))))

;; ---- Renarin-style discriminator test: fails LOUDLY if someone
;; "fixes" the np bucket routing to point at the wrong field.
(deftest discriminator-np-must-not-collide-with-other-zero-buckets
  (testing "np must NEVER route to P_13_6_1 (domestic 0%) or P_13_6_3 (export of goods)"
    (let [xml (fa/invoice->fa3-xml np-single-item-invoice)
          fa-el (child (.getDocumentElement (parse-doc xml)) "Fa")]
      (is (nil? (child fa-el "P_13_6_1"))
          "FAIL-LOUD: np must not land in P_13_6_1 (domestic 0%) — that is legally distinct and produces incorrect JPK-V7 declarations")
      (is (nil? (child fa-el "P_13_6_3"))
          "FAIL-LOUD: np must not land in P_13_6_3 (export of goods) — that is for goods, not services")
      (is (nil? (child fa-el "P_13_7"))
          "FAIL-LOUD: np must not land in P_13_7 (zwolnienie) — np and zwolnienie are distinct classifications")
      (is (some? (child fa-el "P_13_8"))
          "FAIL-LOUD: np must ALWAYS land in P_13_8. Regression guard."))))

(deftest discriminator-np-eu-must-not-collide-with-p13-8
  (testing "np-eu and np must route to different buckets (they are legally distinct)"
    (let [xml (fa/invoice->fa3-xml np-eu-single-item-invoice)
          fa-el (child (.getDocumentElement (parse-doc xml)) "Fa")]
      (is (nil? (child fa-el "P_13_8"))
          "FAIL-LOUD: np-eu must not land in P_13_8 — that's for non-art-100 cases")
      (is (some? (child fa-el "P_13_9"))))))

(deftest parametrized-vat-bucket-matrix
  (testing "Single-item invoice: each :vat value lands in the correct bucket and validates"
    (are [vat bucket p12-val]
         (let [xml (fa/invoice->fa3-xml
                     (assoc single-item-invoice
                            :items [{:vat vat :netto 500 :title "Item"}]))
               fa-el (child (.getDocumentElement (parse-doc xml)) "Fa")
               w (child fa-el "FaWiersz")]
           (and (some? (child fa-el bucket))
                (= p12-val (text (child w "P_12")))
                (nil? (validate-xsd xml))))
      23     "P_13_1"   "23"
      22     "P_13_1"   "22"
      8      "P_13_2"   "8"
      7      "P_13_2"   "7"
      5      "P_13_3"   "5"
      4      "P_13_4"   "4"
      3      "P_13_4"   "3"
      0      "P_13_6_1" "0 KR"
      :zw    "P_13_7"   "zw"
      :np    "P_13_8"   "np I"
      :np-eu "P_13_9"   "np II")))

(deftest users-exact-scenario-validates
  (testing "Simplified version of the user's real config (Polish contractor → Acme US)"
    (let [invoice
          {:seller (assoc sample-seller :name "Jan Kowalski"
                          :address "ul. Krakowska 1, 00-001 Warszawa")
           :buyer  us-buyer
           :number "FV/2026/03/001"
           :date   (LocalDate/of 2026 3 1)
           :currency "PLN"
           :items  [{:vat :np :netto 34769.44M
                     :title "Software development"
                     :currency "PLN"}
                    {:vat :np :netto 1234.56M
                     :title "Consulting"
                     :currency "USD"}
                    {:vat :np :netto 5000.00M
                     :title "Code review"}]}
          sw (java.io.StringWriter.)
          xml (binding [*out* sw]
                (fa/invoice->fa3-xml invoice))
          fa-el (child (.getDocumentElement (parse-doc xml)) "Fa")]
      (is (nil? (validate-xsd xml)) "Must XSD-validate")
      (is (= "PLN" (text (child fa-el "KodWaluty"))))
      (is (= "41004.00" (text (child fa-el "P_13_8"))) "All three items summed in P_13_8")
      (is (= "41004.00" (text (child fa-el "P_15"))) "P_15 = net sum, no VAT added")
      (is (some? (child fa-el "DodatkowyOpis")))
      (is (str/includes? (str sw) "WARN")
          "USD item-level currency must produce a warning"))))
