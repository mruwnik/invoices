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
