(ns invoices.ksef.session-test
  "Unit tests for invoices.ksef.session.

  All HTTP is mocked with `with-redefs` — no network. These tests lock in
  the request *shapes* and the orchestration order; the canonical
  wire-format signal is the integration test against the sandbox."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.test :refer [deftest is testing are]]
            [invoices.ksef.auth :as auth]
            [invoices.ksef.crypto :as crypto]
            [invoices.ksef.session :as session]))

(def ^:private base-url "https://api-test.ksef.mf.gov.pl/v2")
(def ^:private access-token "ACCESS-123")

(defn- json-body [m] {:body m})

(defn- stub-get [responses-atom calls-atom]
  (fn [url & [opts]]
    (swap! calls-atom conj {:method :get :url url :opts opts})
    (let [handler (get @responses-atom url)]
      (when-not handler
        (throw (ex-info (str "No stub for GET " url) {:stubs (keys @responses-atom)})))
      (if (fn? handler) (handler opts) handler))))

(defn- stub-post [responses-atom calls-atom]
  (fn [url & [opts]]
    (let [parsed (some-> (:body opts) (json/parse-string true))]
      (swap! calls-atom conj {:method :post :url url :opts opts :json parsed}))
    (let [handler (get @responses-atom url)]
      (when-not handler
        (throw (ex-info (str "No stub for POST " url) {:stubs (keys @responses-atom)})))
      (if (fn? handler) (handler opts) handler))))

(defn- bearer-value [call]
  (get-in call [:opts :headers "Authorization"]))

;; ---------- valid-ksef-number? ----------

(deftest valid-ksef-number-format-test
  (testing "format/length short-circuits before CRC"
    (are [s] (not (session/valid-ksef-number? s))
      nil
      ""
      "5265877635-20250826-0100001AF629"                ; 32 chars, missing -CRC
      "5265877635-20250826-0100001AF629-A"              ; 34 chars
      "5265877635-20250826-0100001AF629-AFX"            ; 36 chars
      "5265877635 20250826 0100001AF629 AF"             ; spaces, not dashes
      "5265877635-20250826-0100001af629-AF"             ; lowercase hex
      "5265877635-20250826-0100001AG629-AF"             ; G not valid hex
      "526587763A-20250826-0100001AF629-AF")))          ; non-digit NIP

(deftest valid-ksef-number-crc-test
  (testing "canonical example from numer-ksef.md validates"
    (is (session/valid-ksef-number? "5265877635-20250826-0100001AF629-AF")))
  (testing "flipping any checksum bit invalidates"
    (is (not (session/valid-ksef-number? "5265877635-20250826-0100001AF629-AE")))
    (is (not (session/valid-ksef-number? "5265877635-20250826-0100001AF629-00"))))
  (testing "flipping a payload char recomputes a different CRC"
    (is (not (session/valid-ksef-number? "5265877635-20250826-0100001AF628-AF")))))

;; ---------- fetch-session-encryption-key ----------

(deftest fetch-session-encryption-key-test
  (testing "picks the first cert whose usage array contains SymmetricKeyEncryption"
    (let [calls (atom [])
          picked (atom nil)
          responses (atom {(str base-url "/security/public-key-certificates")
                           (json-body [{:usage ["KsefTokenEncryption"] :certificate "WRONG-1"}
                                       {:usage ["SymmetricKeyEncryption"] :certificate "RIGHT"}
                                       {:usage ["KsefTokenEncryption" "SymmetricKeyEncryption"] :certificate "WRONG-2"}])})]
      (with-redefs [http/get (stub-get responses calls)
                    crypto/parse-x509-cert-der (fn [c] (reset! picked c) ::fake-pk)]
        (is (= ::fake-pk (session/fetch-session-encryption-key base-url))))
      (is (= "RIGHT" @picked) "first SymmetricKeyEncryption cert wins")))

  (testing "throws when no cert has SymmetricKeyEncryption usage"
    (let [calls (atom [])
          responses (atom {(str base-url "/security/public-key-certificates")
                           (json-body [{:usage ["KsefTokenEncryption"] :certificate "nope"}])})]
      (with-redefs [http/get (stub-get responses calls)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No SymmetricKeyEncryption"
                              (session/fetch-session-encryption-key base-url)))))))

;; ---------- open-online-session ----------

(deftest open-online-session-test
  (testing "posts formCode + encryption block and extracts referenceNumber"
    (let [calls (atom [])
          responses (atom {(str base-url "/sessions/online")
                           (json-body {:referenceNumber "SES-1" :validUntil "2026-04-14T22:00:00Z"})})]
      (with-redefs [http/post (stub-post responses calls)]
        (is (= "SES-1"
               (session/open-online-session
                 base-url access-token (:fa-3 session/schemas)
                 "ENCKEY-B64" "IV-B64"))))
      (let [req (first @calls)
            body (:json req)]
        (is (= (str base-url "/sessions/online") (:url req)))
        (is (= "Bearer ACCESS-123" (bearer-value req)))
        (is (= "FA (3)" (get-in body [:formCode :systemCode])))
        (is (= "1-0E"   (get-in body [:formCode :schemaVersion])))
        (is (= "FA"     (get-in body [:formCode :value])))
        (is (= "ENCKEY-B64" (get-in body [:encryption :encryptedSymmetricKey])))
        (is (= "IV-B64"     (get-in body [:encryption :initializationVector])))))))

;; ---------- send-invoice-to-session ----------

(deftest send-invoice-to-session-test
  (testing "URL contains session-ref; body carries all five required fields"
    (let [calls (atom [])
          responses (atom {(str base-url "/sessions/online/SES-1/invoices")
                           (json-body {:referenceNumber "INV-99"})})]
      (with-redefs [http/post (stub-post responses calls)]
        (is (= "INV-99"
               (session/send-invoice-to-session
                 base-url access-token "SES-1"
                 {:invoice-hash "HASH-PT"
                  :invoice-size 1234
                  :encrypted-invoice-hash "HASH-CT"
                  :encrypted-invoice-size 1248
                  :encrypted-invoice-content "CT-B64"}))))
      (let [req (first @calls)
            body (:json req)]
        (is (= (str base-url "/sessions/online/SES-1/invoices") (:url req)))
        (is (= "Bearer ACCESS-123" (bearer-value req)))
        (is (= "HASH-PT" (:invoiceHash body)))
        (is (= 1234      (:invoiceSize body)))
        (is (= "HASH-CT" (:encryptedInvoiceHash body)))
        (is (= 1248      (:encryptedInvoiceSize body)))
        (is (= "CT-B64"  (:encryptedInvoiceContent body)))))))

;; ---------- close-session ----------

(deftest close-session-test
  (testing "POSTs to /close with bearer; 204 response parses without trying to read body"
    (let [calls (atom [])
          responses (atom {(str base-url "/sessions/online/SES-1/close")
                           {:status 204}})]
      (with-redefs [http/post (stub-post responses calls)]
        (is (nil? (session/close-session base-url access-token "SES-1"))))
      (is (= 1 (count @calls)))
      (is (= (str base-url "/sessions/online/SES-1/close") (:url (first @calls))))
      (is (= "Bearer ACCESS-123" (bearer-value (first @calls)))))))

;; ---------- poll-session-processed ----------

(deftest poll-session-processed-happy-path-test
  (testing "waits for status.code==200 AND non-empty upo.pages"
    (let [calls (atom [])
          seq* (atom [(json-body {:status {:code 150 :description "Processing"}})
                      ;; code 200 but no UPO yet → keep polling
                      (json-body {:status {:code 200 :description "OK"} :upo {:pages []}})
                      (json-body {:status {:code 200 :description "OK"}
                                  :upo {:pages [{:referenceNumber "UPO-1"}]}})])
          responses (atom {(str base-url "/sessions/SES-1")
                           (fn [_opts]
                             (let [[h & r] @seq*] (reset! seq* r) h))})]
      (with-redefs [http/get (stub-get responses calls)]
        (let [body (session/poll-session-processed
                     base-url access-token "SES-1"
                     :interval-ms 1 :timeout-ms 5000)]
          (is (= 200 (get-in body [:status :code])))
          (is (seq (get-in body [:upo :pages])))))
      (is (= 3 (count @calls)))
      (doseq [c @calls]
        (is (= "Bearer ACCESS-123" (bearer-value c)))))))

(deftest poll-session-processed-failure-returns-body-test
  (testing "terminal non-1xx non-200 code returns the body unchanged — the caller
            (submit-invoice) inspects it to decide whether a per-invoice fallback
            lookup can recover an `originalKsefNumber`. Historically this threw
            ex-info, which made the duplicate-detection fallback path dead code."
    (let [calls (atom [])
          failure-body {:status {:code 445 :description "Brak poprawnych faktur"}
                        :invoiceCount 1 :failedInvoiceCount 1}
          responses (atom {(str base-url "/sessions/SES-1")
                           (json-body failure-body)})]
      (with-redefs [http/get (stub-get responses calls)]
        (is (= failure-body
               (session/poll-session-processed
                 base-url access-token "SES-1"
                 :interval-ms 1 :timeout-ms 5000)))))))

(deftest poll-session-processed-timeout-test
  (testing "stays in 1xx past deadline → throws timeout ex-info"
    (let [calls (atom [])
          responses (atom {(str base-url "/sessions/SES-1")
                           (json-body {:status {:code 150 :description "Processing"}})})]
      (with-redefs [http/get (stub-get responses calls)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Timed out"
                              (session/poll-session-processed
                                base-url access-token "SES-1"
                                :interval-ms 1 :timeout-ms 5)))))))

;; ---------- fetch-invoice-status ----------

(deftest fetch-invoice-status-test
  (testing "GETs /sessions/{ref}/invoices/{invRef} and returns body"
    (let [calls (atom [])
          responses (atom {(str base-url "/sessions/SES-1/invoices/INV-99")
                           (json-body {:referenceNumber "INV-99"
                                       :ksefNumber "5265877635-20250826-0100001AF629-AF"
                                       :status {:code 200 :description "Sukces"}})})]
      (with-redefs [http/get (stub-get responses calls)]
        (let [body (session/fetch-invoice-status base-url access-token "SES-1" "INV-99")]
          (is (= "5265877635-20250826-0100001AF629-AF" (:ksefNumber body)))))
      (is (= "Bearer ACCESS-123" (bearer-value (first @calls)))))))

;; ---------- fetch-invoice-upo ----------

(deftest fetch-invoice-upo-test
  (testing "GET /upo returns raw XML string (:as :string, Accept: application/xml)"
    (let [calls (atom [])
          xml "<?xml version=\"1.0\"?><UPO><signed/></UPO>"
          responses (atom {(str base-url "/sessions/SES-1/invoices/INV-99/upo")
                           {:body xml}})]
      (with-redefs [http/get (stub-get responses calls)]
        (is (= xml (session/fetch-invoice-upo base-url access-token "SES-1" "INV-99"))))
      (let [req (first @calls)]
        (is (= "Bearer ACCESS-123" (bearer-value req)))
        (is (= "application/xml" (get-in req [:opts :headers "Accept"])))
        (is (= :string (get-in req [:opts :as])) ":as :string — do NOT parse as JSON")))))

;; ---------- submit-invoice end-to-end ----------

(deftest submit-invoice-end-to-end-test
  (testing "full happy-path session dance hits endpoints in order, byte counts are UTF-8 bytes"
    (let [;; FA(3) XML with Polish multi-byte UTF-8 chars so char count != byte count
          ;; "ąż" is 4 bytes (2 chars × 2 bytes each in UTF-8).
          invoice-xml "<Faktura>ąż</Faktura>"
          plaintext-bytes (.getBytes invoice-xml "UTF-8")
          calls (atom [])
          certs-url   (str base-url "/security/public-key-certificates")
          open-url    (str base-url "/sessions/online")
          send-url    (str base-url "/sessions/online/SES-1/invoices")
          close-url   (str base-url "/sessions/online/SES-1/close")
          status-url  (str base-url "/sessions/SES-1")
          inv-url     (str base-url "/sessions/SES-1/invoices/INV-99")
          upo-url     (str base-url "/sessions/SES-1/invoices/INV-99/upo")
          upo-xml     "<UPO/>"
          ksef-num    "5265877635-20250826-0100001AF629-AF"
          gets (atom {certs-url  (json-body [{:usage ["SymmetricKeyEncryption"] :certificate "DER"}])
                      status-url (json-body {:status {:code 200 :description "OK"}
                                             :upo {:pages [{:referenceNumber "UPO-R"}]}})
                      inv-url    (json-body {:referenceNumber "INV-99"
                                             :ksefNumber ksef-num
                                             :status {:code 200 :description "Sukces"}})
                      upo-url    {:body upo-xml}})
          posts (atom {open-url  (json-body {:referenceNumber "SES-1" :validUntil "u"})
                       send-url  (json-body {:referenceNumber "INV-99"})
                       close-url {:status 204}})]
      (with-redefs [http/get  (stub-get gets calls)
                    http/post (stub-post posts calls)
                    crypto/parse-x509-cert-der (fn [_] ::fake-pk)
                    crypto/generate-aes-key (fn [] (reify javax.crypto.SecretKey
                                                     (getEncoded [_] (byte-array 32 (byte 0)))
                                                     (getAlgorithm [_] "AES")
                                                     (getFormat [_] "RAW")))
                    crypto/generate-iv (fn [] (byte-array 16 (byte 0)))
                    crypto/rsa-oaep-sha256-encrypt (fn [_ _] (byte-array [1 2 3]))
                    crypto/aes-256-cbc-encrypt (fn [_ _ ^bytes pt]
                                                 ;; Ciphertext is at least one padded block larger
                                                 (byte-array (+ (alength pt) 16) (byte 0)))]
        (let [result (session/submit-invoice {:base-url base-url
                                               :access-token access-token
                                               :invoice-xml invoice-xml
                                               :schema :fa-3
                                               :poll-interval-ms 1
                                               :poll-timeout-ms 5000})]
          (is (= {:ksef-number ksef-num
                  :upo-xml upo-xml
                  :invoice-ref "INV-99"
                  :session-ref "SES-1"}
                 result))))

      (testing "endpoints hit in the documented order"
        (is (= [certs-url open-url send-url close-url status-url inv-url upo-url]
               (mapv :url @calls))))

      (testing "invoiceSize is the UTF-8 BYTE count, not the char count"
        (let [send-req (some #(when (= send-url (:url %)) %) @calls)
              body (:json send-req)]
          (is (= (alength plaintext-bytes) (:invoiceSize body)))
          (is (not= (count invoice-xml) (:invoiceSize body))
              "ąż makes byte count > char count")))

      (testing "encryptedInvoiceSize matches the ciphertext byte length"
        (let [send-req (some #(when (= send-url (:url %)) %) @calls)
              body (:json send-req)]
          (is (= (+ (alength plaintext-bytes) 16) (:encryptedInvoiceSize body)))))

      (testing "every authenticated call carries the Bearer access token"
        (doseq [c @calls
                :when (not= certs-url (:url c))]
          (is (= "Bearer ACCESS-123" (bearer-value c))
              (str "missing/bad bearer on " (:url c))))))))

(deftest submit-invoice-rejects-bad-ksef-number-test
  (testing "if KSeF returns a malformed numer KSeF, submit-invoice throws"
    (let [invoice-xml "<Faktura/>"
          calls (atom [])
          gets (atom {(str base-url "/security/public-key-certificates")
                      (json-body [{:usage ["SymmetricKeyEncryption"] :certificate "DER"}])
                      (str base-url "/sessions/SES-1")
                      (json-body {:status {:code 200 :description "OK"}
                                  :upo {:pages [{:referenceNumber "UPO-R"}]}})
                      (str base-url "/sessions/SES-1/invoices/INV-99")
                      (json-body {:ksefNumber "NOT-A-VALID-KSEF-NUMBER"
                                  :status {:code 200}})})
          posts (atom {(str base-url "/sessions/online")
                       (json-body {:referenceNumber "SES-1"})
                       (str base-url "/sessions/online/SES-1/invoices")
                       (json-body {:referenceNumber "INV-99"})
                       (str base-url "/sessions/online/SES-1/close")
                       {:status 204}})]
      (with-redefs [http/get  (stub-get gets calls)
                    http/post (stub-post posts calls)
                    crypto/parse-x509-cert-der (fn [_] ::fake-pk)
                    crypto/generate-aes-key (fn [] (reify javax.crypto.SecretKey
                                                     (getEncoded [_] (byte-array 32 (byte 0)))
                                                     (getAlgorithm [_] "AES")
                                                     (getFormat [_] "RAW")))
                    crypto/generate-iv (fn [] (byte-array 16 (byte 0)))
                    crypto/rsa-oaep-sha256-encrypt (fn [_ _] (byte-array [1]))
                    crypto/aes-256-cbc-encrypt (fn [_ _ pt] pt)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed numer KSeF"
                              (session/submit-invoice {:base-url base-url
                                                        :access-token access-token
                                                        :invoice-xml invoice-xml
                                                        :poll-interval-ms 1
                                                        :poll-timeout-ms 5000})))))))

(deftest submit-invoice-duplicate-fallback-test
  (testing "session fails with 445 (brak poprawnych faktur), per-invoice lookup
            returns 440 (Duplikat faktury) carrying the originalKsefNumber.
            submit-invoice must (a) NOT throw from the session failure,
            (b) extract the originalKsefNumber, (c) skip the UPO fetch
            entirely — KSeF only serves UPO for the original submission,
            not the duplicate — and (d) return the fallback number with
            :upo-xml nil. This is the Renarin-flagged latent path."
    (let [invoice-xml "<Faktura/>"
          calls (atom [])
          original-ksef "5265877635-20250826-0100001AF629-AF"
          certs-url  (str base-url "/security/public-key-certificates")
          open-url   (str base-url "/sessions/online")
          send-url   (str base-url "/sessions/online/SES-DUP/invoices")
          close-url  (str base-url "/sessions/online/SES-DUP/close")
          status-url (str base-url "/sessions/SES-DUP")
          inv-url    (str base-url "/sessions/SES-DUP/invoices/INV-DUP")
          upo-url    (str base-url "/sessions/SES-DUP/invoices/INV-DUP/upo")
          upo-called? (atom false)
          gets (atom {certs-url  (json-body [{:usage ["SymmetricKeyEncryption"] :certificate "DER"}])
                      status-url (json-body {:status {:code 445
                                                      :description "Brak poprawnych faktur"}
                                              :invoiceCount 1
                                              :failedInvoiceCount 1})
                      inv-url    (json-body {:referenceNumber "INV-DUP"
                                              :ksefNumber nil
                                              :status {:code 440
                                                       :description "Duplikat faktury"
                                                       :extensions {:originalKsefNumber original-ksef
                                                                    :originalSessionReferenceNumber "SES-ORIG"}}})
                      upo-url    (fn [_] (reset! upo-called? true)
                                   (throw (ex-info "UPO must not be fetched on duplicate path" {})))})
          posts (atom {open-url  (json-body {:referenceNumber "SES-DUP"})
                       send-url  (json-body {:referenceNumber "INV-DUP"})
                       close-url {:status 204}})]
      (with-redefs [http/get  (stub-get gets calls)
                    http/post (stub-post posts calls)
                    crypto/parse-x509-cert-der (fn [_] ::fake-pk)
                    crypto/generate-aes-key (fn [] (reify javax.crypto.SecretKey
                                                     (getEncoded [_] (byte-array 32 (byte 0)))
                                                     (getAlgorithm [_] "AES")
                                                     (getFormat [_] "RAW")))
                    crypto/generate-iv (fn [] (byte-array 16 (byte 0)))
                    crypto/rsa-oaep-sha256-encrypt (fn [_ _] (byte-array [1]))
                    crypto/aes-256-cbc-encrypt (fn [_ _ pt] pt)]
        (let [result (session/submit-invoice {:base-url base-url
                                                :access-token access-token
                                                :invoice-xml invoice-xml
                                                :poll-interval-ms 1
                                                :poll-timeout-ms 5000})]
          (is (= original-ksef (:ksef-number result)))
          (is (nil? (:upo-xml result)) ":upo-xml nil on duplicate — no UPO exists")
          (is (= "INV-DUP" (:invoice-ref result)))
          (is (= "SES-DUP" (:session-ref result)))))
      (is (false? @upo-called?)
          "UPO endpoint must not be called when the session failed — KSeF would 404")
      (testing "endpoints hit in order — UPO is absent"
        (is (= [certs-url open-url send-url close-url status-url inv-url]
               (mapv :url @calls)))))))

(deftest submit-invoice-fallback-lookup-breadcrumb-test
  (testing "when the per-invoice fallback lookup itself throws, submit-invoice
            surfaces a breadcrumb to stdout (so incident debugging can see
            the lookup failure) AND continues into the no-fallback path,
            which then throws the normal 'no fallback ksefNumber' ex-info."
    (let [invoice-xml "<Faktura/>"
          calls (atom [])
          gets (atom {(str base-url "/security/public-key-certificates")
                      (json-body [{:usage ["SymmetricKeyEncryption"] :certificate "DER"}])
                      (str base-url "/sessions/SES-P")
                      (json-body {:status {:code 445 :description "Brak poprawnych faktur"}})})
          posts (atom {(str base-url "/sessions/online")
                       (json-body {:referenceNumber "SES-P"})
                       (str base-url "/sessions/online/SES-P/invoices")
                       (json-body {:referenceNumber "INV-P"})
                       (str base-url "/sessions/online/SES-P/close")
                       {:status 204}})]
      (with-redefs [http/get  (stub-get gets calls)
                    http/post (stub-post posts calls)
                    session/fetch-invoice-status (fn [& _] (throw (Exception. "probe-boom")))
                    crypto/parse-x509-cert-der (fn [_] ::fake-pk)
                    crypto/generate-aes-key (fn [] (reify javax.crypto.SecretKey
                                                     (getEncoded [_] (byte-array 32 (byte 0)))
                                                     (getAlgorithm [_] "AES")
                                                     (getFormat [_] "RAW")))
                    crypto/generate-iv (fn [] (byte-array 16 (byte 0)))
                    crypto/rsa-oaep-sha256-encrypt (fn [_ _] (byte-array [1]))
                    crypto/aes-256-cbc-encrypt (fn [_ _ pt] pt)]
        (let [out (java.io.StringWriter.)
              thrown (atom nil)]
          (binding [*out* out]
            (try
              (session/submit-invoice {:base-url base-url
                                        :access-token access-token
                                        :invoice-xml invoice-xml
                                        :poll-interval-ms 1
                                        :poll-timeout-ms 5000})
              (catch clojure.lang.ExceptionInfo e
                (reset! thrown e))))
          (is (some? @thrown) "expected no-fallback ex-info after lookup failure")
          (is (re-find #"no fallback ksefNumber" (.getMessage @thrown)))
          (is (re-find #"duplicate-fallback lookup failed.*probe-boom" (str out))
              "breadcrumb for the swallowed exception must reach stdout"))))))

(deftest submit-invoice-session-failed-no-fallback-test
  (testing "session fails AND no fallback ksefNumber on the per-invoice record →
            submit-invoice throws ex-info carrying both statuses for debugging"
    (let [invoice-xml "<Faktura/>"
          calls (atom [])
          gets (atom {(str base-url "/security/public-key-certificates")
                      (json-body [{:usage ["SymmetricKeyEncryption"] :certificate "DER"}])
                      (str base-url "/sessions/SES-X")
                      (json-body {:status {:code 445 :description "Brak poprawnych faktur"}})
                      (str base-url "/sessions/SES-X/invoices/INV-X")
                      (json-body {:referenceNumber "INV-X"
                                  :status {:code 430 :description "Walidacja biznesowa"}})})
          posts (atom {(str base-url "/sessions/online")
                       (json-body {:referenceNumber "SES-X"})
                       (str base-url "/sessions/online/SES-X/invoices")
                       (json-body {:referenceNumber "INV-X"})
                       (str base-url "/sessions/online/SES-X/close")
                       {:status 204}})]
      (with-redefs [http/get  (stub-get gets calls)
                    http/post (stub-post posts calls)
                    crypto/parse-x509-cert-der (fn [_] ::fake-pk)
                    crypto/generate-aes-key (fn [] (reify javax.crypto.SecretKey
                                                     (getEncoded [_] (byte-array 32 (byte 0)))
                                                     (getAlgorithm [_] "AES")
                                                     (getFormat [_] "RAW")))
                    crypto/generate-iv (fn [] (byte-array 16 (byte 0)))
                    crypto/rsa-oaep-sha256-encrypt (fn [_ _] (byte-array [1]))
                    crypto/aes-256-cbc-encrypt (fn [_ _ pt] pt)]
        (try
          (session/submit-invoice {:base-url base-url
                                    :access-token access-token
                                    :invoice-xml invoice-xml
                                    :poll-interval-ms 1
                                    :poll-timeout-ms 5000})
          (is false "expected ex-info, got clean return")
          (catch clojure.lang.ExceptionInfo e
            (is (re-find #"no fallback ksefNumber" (.getMessage e)))
            (let [data (ex-data e)]
              (is (= 445 (get-in data [:session-status :code])))
              (is (= 430 (get-in data [:invoice-status :status :code]))))))))))

;; ---------- composed auth → session (Renarin mock-depth rule) ----------

(deftest auth-then-session-composition-test
  (testing "auth/authenticate → session/submit-invoice wired through a single
            clj-http mock layer — catches bearer-threading and ordering bugs
            that would be hidden by stubbing out authenticate entirely"
    (let [invoice-xml "<Faktura>ł</Faktura>"
          calls (atom [])
          ;; auth endpoints
          challenge-url (str base-url "/auth/challenge")
          certs-url     (str base-url "/security/public-key-certificates")
          ksef-url      (str base-url "/auth/ksef-token")
          auth-poll-url (str base-url "/auth/AUTH-REF-1")
          redeem-url    (str base-url "/auth/token/redeem")
          ;; session endpoints
          open-url      (str base-url "/sessions/online")
          send-url      (str base-url "/sessions/online/SES-7/invoices")
          close-url     (str base-url "/sessions/online/SES-7/close")
          sess-poll-url (str base-url "/sessions/SES-7")
          inv-url       (str base-url "/sessions/SES-7/invoices/INV-5")
          upo-url       (str base-url "/sessions/SES-7/invoices/INV-5/upo")
          ksef-num      "5265877635-20250826-0100001AF629-AF"
          upo-xml       "<UPO signed=\"true\"/>"
          ;; One cert response shared across auth + session — same endpoint,
          ;; same fixture, two different usages present.
          cert-resp (json-body [{:usage ["KsefTokenEncryption"]    :certificate "TOKEN-CERT"}
                                {:usage ["SymmetricKeyEncryption"] :certificate "SESSION-CERT"}])
          gets (atom {certs-url     cert-resp
                      auth-poll-url (json-body {:status {:code 200 :description "OK"}})
                      sess-poll-url (json-body {:status {:code 200 :description "OK"}
                                                :upo {:pages [{:referenceNumber "UPO-R"}]}})
                      inv-url       (json-body {:referenceNumber "INV-5"
                                                :ksefNumber ksef-num
                                                :status {:code 200 :description "Sukces"}})
                      upo-url       {:body upo-xml}})
          posts (atom {challenge-url (json-body {:challenge "C" :timestampMs 111})
                       ksef-url      (json-body {:referenceNumber "AUTH-REF-1"
                                                 :authenticationToken {:token "OP-TOK"}})
                       redeem-url    (json-body {:accessToken  {:token "ACCESS-COMPOSED"
                                                                :validUntil "u1"}
                                                 :refreshToken {:token "R" :validUntil "u2"}})
                       open-url      (json-body {:referenceNumber "SES-7"})
                       send-url      (json-body {:referenceNumber "INV-5"})
                       close-url     {:status 204}})]
      (with-redefs [http/get  (stub-get gets calls)
                    http/post (stub-post posts calls)
                    ;; Crypto is stubbed ONLY so the test doesn't need real
                    ;; key material — nothing higher in the stack is hidden.
                    crypto/parse-x509-cert-der (fn [_] ::fake-pk)
                    crypto/rsa-oaep-sha256-encrypt (fn [_ _] (byte-array [9 9 9]))
                    crypto/generate-aes-key (fn [] (reify javax.crypto.SecretKey
                                                     (getEncoded [_] (byte-array 32 (byte 0)))
                                                     (getAlgorithm [_] "AES")
                                                     (getFormat [_] "RAW")))
                    crypto/generate-iv (fn [] (byte-array 16 (byte 0)))
                    crypto/aes-256-cbc-encrypt (fn [_ _ ^bytes pt] pt)]
        (let [{:keys [access-token]} (auth/authenticate
                                       {:base-url base-url
                                        :nip 6423166047
                                        :token "ref|ctx|secret"
                                        :poll-interval-ms 1
                                        :poll-timeout-ms 5000})
              result (session/submit-invoice
                       {:base-url base-url
                        :access-token access-token
                        :invoice-xml invoice-xml
                        :schema :fa-3
                        :poll-interval-ms 1
                        :poll-timeout-ms 5000})]
          (is (= "ACCESS-COMPOSED" access-token))
          (is (= {:ksef-number ksef-num
                  :upo-xml upo-xml
                  :invoice-ref "INV-5"
                  :session-ref "SES-7"}
                 result))))

      (testing "all 12 endpoints hit in the documented composition order"
        (is (= [;; auth dance
                challenge-url certs-url ksef-url auth-poll-url redeem-url
                ;; session dance
                certs-url open-url send-url close-url sess-poll-url inv-url upo-url]
               (mapv :url @calls))))

      (testing "auth-phase calls use OP-TOK bearer; session-phase calls use ACCESS-COMPOSED"
        (let [by-url (group-by :url @calls)]
          ;; auth polling + redeem carry the operation token
          (is (= "Bearer OP-TOK" (bearer-value (first (get by-url auth-poll-url)))))
          (is (= "Bearer OP-TOK" (bearer-value (first (get by-url redeem-url)))))
          ;; session calls carry the minted access token
          (doseq [u [open-url send-url close-url sess-poll-url inv-url upo-url]]
            (is (= "Bearer ACCESS-COMPOSED" (bearer-value (first (get by-url u))))
                (str "session phase should use access token on " u))))))))
