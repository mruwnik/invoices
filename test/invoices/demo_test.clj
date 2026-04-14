(ns invoices.demo-test
  "Parametrized regression tests over the demo configs under `demo/configs`.

  Each demo config is loaded as plain EDN, runs through the same
  `prepare-invoice`/XML path that production uses, and is compared to a
  committed golden FA(3) XML under `demo/golden/`. The timestamp field
  (`DataWytworzeniaFa`) is pinned to a fixed value so the comparison is
  byte-stable.

  When `UPDATE_DEMO_GOLDENS=1` is set, the test rewrites golden files
  from the current XML instead of asserting equality. Use this after an
  intentional change — never in CI."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [invoices.calc :refer [set-price]]
            [invoices.ksef.xml :as ksef-xml])
  (:import [java.time LocalDate]))

(def ^:private demo-date (LocalDate/of 2026 4 14))
(def ^:private fixed-timestamp "2026-04-14T12:00:00Z")

(def ^:private configs-dir (io/file "demo" "configs"))
(def ^:private golden-dir  (io/file "demo" "golden"))

(defn- load-config [^java.io.File f]
  (-> f slurp edn/read-string))

(defn- prepare-invoice
  "Same assembly the production code does: merge seller, compute prices,
  attach the :date + :number that the xml generator reads. Demo configs
  don't use worklogs, so price calculation is a no-op identity pass
  (items already have :netto set)."
  [{seller :seller font :font-path} invoice]
  (-> invoice
      (update :seller (partial merge seller))
      (assoc :font-path font)
      (update :items (fn [items]
                       (->> items
                            (map #(set-price nil %))
                            (remove (comp nil? :netto)))))
      (assoc :date demo-date :number "1/4/2026")))

(defn- config-invoice
  "Load a demo config file and return its (first, only) invoice fully
  prepared for XML generation."
  [^java.io.File config-file]
  (let [cfg (load-config config-file)]
    (prepare-invoice cfg (-> cfg :invoices first))))

(defn- render-xml
  "Generate FA(3) XML with the DataWytworzeniaFa timestamp pinned so the
  golden comparison is byte-stable. `utc-now` is private but still a var,
  so `with-redefs` can pin it."
  [invoice]
  (with-redefs [invoices.ksef.xml/utc-now (constantly fixed-timestamp)]
    (ksef-xml/invoice->fa3-xml invoice)))

(defn- golden-file [config-name]
  (io/file golden-dir (str config-name ".fa3.xml")))

(defn- config-file [config-name]
  (io/file configs-dir (str config-name ".edn")))

(def ^:private demo-configs
  "Config name → assertions about the rendered XML (in addition to the
  golden file comparison). Each entry is a vector of `[substring
  description]` pairs. The goal is that if someone regenerates a golden
  without looking at it, these still fire."
  {"non-eu-services"
   [["<KodKraju>US</KodKraju>" "US country code on buyer address"]
    ["<BrakID>1</BrakID>"      "no-NIP buyer routes through BrakID"]
    ["<P_13_8>14000.00</P_13_8>" "all three :np items sum to P_13_8"]
    ["<P_12>np I</P_12>"       "each line carries np I stawka"]
    ["art. 28b"                "legal basis annotation in DodatkowyOpis"]
    ["<P_15>14000.00</P_15>"   "total equals net (no VAT added)"]]

   "polish-domestic"
   [["<NIP>9875645342</NIP>"   "domestic buyer uses <NIP>"]
    ["<P_13_1>1200.00</P_13_1>" "23% line routes to P_13_1"]
    ["<P_14_1>276.00</P_14_1>"  "23% VAT = 276.00"]
    ["<P_13_2>450.00</P_13_2>"  "8% line routes to P_13_2"]
    ["<P_13_3>200.00</P_13_3>"  "5% line routes to P_13_3"]
    ["<P_13_7>100.00</P_13_7>"  "zw line routes to P_13_7"]
    ["<P_19>1</P_19>"           "Zwolnienie flag flipped on for any zw item"]]

   "polish-domestic-with-ksef"
   ;; FA(3) body identical to polish-domestic (the :ksef block is
   ;; metadata for submission, not XML content). Just re-check the
   ;; structural anchors so a drift is loud.
   [["<NIP>9875645342</NIP>"   "buyer NIP still present"]
    ["<P_13_1>1200.00</P_13_1>" "std bucket unchanged"]
    ["<P_19>1</P_19>"           "zw flag unchanged"]]

   "eu-intra-community"
   [["<KodUE>DE</KodUE>"        "EU country code"]
    ["<NrVatUE>123456789</NrVatUE>" "EU VAT-UE id, country prefix stripped"]
    ["<P_13_9>9000.00</P_13_9>" "two :np-eu items sum to P_13_9"]
    ["<P_12>np II</P_12>"       "lines carry np II stawka"]
    ["art. 100"                 "np-eu legal basis annotation"]]

   "mixed-items"
   [["<P_13_1>1000.00</P_13_1>"   "std 23% bucket"]
    ["<P_14_1>230.00</P_14_1>"    "23% VAT computed"]
    ["<P_13_2>500.00</P_13_2>"    "red1 8% bucket"]
    ["<P_13_3>250.00</P_13_3>"    "red2 5% bucket"]
    ["<P_13_6_1>125.00</P_13_6_1>" "0 KR bucket"]
    ["<P_13_7>75.00</P_13_7>"     "zw bucket"]
    ["<P_13_8>4000.00</P_13_8>"   "np I bucket"]
    ["<P_13_9>2000.00</P_13_9>"   "np II bucket"]]})

(defn- update-goldens? []
  (= "1" (System/getenv "UPDATE_DEMO_GOLDENS")))

(defn- strip-ns-prefix
  "Drop the clojure.data.xml `a:` namespace prefix from tags so substring
  assertions can target bare FA(3) element names. Keeps the test
  expectations readable."
  [^String xml]
  (str/replace xml #"</?a:" (fn [m] (if (.startsWith m "</") "</" "<"))))

(defn- check-one [config-name substring-checks]
  (testing config-name
    (let [cfg-file (config-file config-name)
          invoice  (config-invoice cfg-file)
          xml      (render-xml invoice)
          golden   (golden-file config-name)
          stripped (strip-ns-prefix xml)]
      (doseq [[needle desc] substring-checks]
        (is (str/includes? stripped needle)
            (str config-name " — " desc " — expected substring: " needle)))
      (if (update-goldens?)
        (do (.mkdirs golden-dir)
            (spit golden xml)
            (println "  UPDATE_DEMO_GOLDENS — rewrote" (.getPath golden)))
        (do
          (is (.exists golden)
              (str "golden file missing: " (.getPath golden)
                   " — run UPDATE_DEMO_GOLDENS=1 clj -M:test to create"))
          (when (.exists golden)
            (is (= (slurp golden) xml)
                (str config-name " — golden XML mismatch. "
                     "If this was an intentional change, rerun with "
                     "UPDATE_DEMO_GOLDENS=1 clj -M:test"))))))))

(deftest all-demo-configs-match-golden
  (doseq [[config-name checks] demo-configs]
    (check-one config-name checks)))

(deftest non-eu-is-headline-use-case
  (testing "non-eu-services is the user's primary case — extra assertions"
    (let [invoice  (config-invoice (config-file "non-eu-services"))
          stripped (strip-ns-prefix (render-xml invoice))]
      (is (not (str/includes? stripped "<NIP>US"))
          "FAIL-LOUD: US buyer must not emit <NIP> (digits-only schema would reject it anyway)")
      (is (not (str/includes? stripped "<NrVatUE>"))
          "FAIL-LOUD: US is not EU, NrVatUE is wrong")
      (is (not (str/includes? stripped "<NrID>"))
          "US buyer has no :nip → BrakID path, not NrID")
      (is (not (str/includes? stripped "<P_14_8>"))
          "FA(3) has no P_14_8 — np items carry no Polish VAT"))))
