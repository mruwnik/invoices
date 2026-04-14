(ns invoices.ksef.auth-test
  "Unit tests for invoices.ksef.auth.

  Every HTTP call is mocked with with-redefs — no real network. The sandbox
  has its own integration test (blocked behind wiring) which is the canonical
  signal that the wire format is right; these tests lock in the request
  *shapes* so a drifted refactor fails loudly here instead of silently in
  production."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.test :refer [deftest is testing]]
            [invoices.ksef.auth :as auth]
            [invoices.ksef.crypto :as crypto]))

(def ^:private base-url "https://api-test.ksef.mf.gov.pl/v2")

(defn- json-body
  "clj-http returns :body as already-parsed-into-keywords JSON when :as :json.
  Tests build the same structure directly."
  [m] {:body m})

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

(deftest fetch-challenge-test
  (testing "uses numeric timestampMs, not the ISO8601 timestamp string"
    (let [calls (atom [])
          responses (atom {(str base-url "/auth/challenge")
                           (json-body {:challenge "abc-123"
                                       :timestamp "2026-04-14T19:30:00Z"
                                       :timestampMs 1744659000000
                                       :clientIp "1.2.3.4"})})]
      (with-redefs [http/post (stub-post responses calls)]
        (is (= {:challenge "abc-123" :timestamp-ms 1744659000000}
               (auth/fetch-challenge base-url))))
      (is (= 1 (count @calls)))
      (is (= (str base-url "/auth/challenge") (:url (first @calls)))))))

(deftest fetch-token-encryption-key-test
  (testing "picks the cert whose usage array contains KsefTokenEncryption"
    (let [calls (atom [])
          picked-cert (atom nil)
          responses (atom {(str base-url "/security/public-key-certificates")
                           (json-body [{:usage ["SymmetricKeyEncryption"] :certificate "WRONG-A"}
                                       {:usage ["KsefTokenEncryption"]    :certificate "RIGHT"}
                                       {:usage ["KsefTokenEncryption" "Other"] :certificate "WRONG-B"}])})]
      (with-redefs [http/get (stub-get responses calls)
                    crypto/parse-x509-cert-der (fn [c]
                                                 (reset! picked-cert c)
                                                 ::fake-public-key)]
        (is (= ::fake-public-key (auth/fetch-token-encryption-key base-url))))
      (is (= "RIGHT" @picked-cert)
          "first cert with KsefTokenEncryption usage wins")))

  (testing "throws when no cert has KsefTokenEncryption usage"
    (let [calls (atom [])
          responses (atom {(str base-url "/security/public-key-certificates")
                           (json-body [{:usage ["SymmetricKeyEncryption"] :certificate "nope"}])})]
      (with-redefs [http/get (stub-get responses calls)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No KsefTokenEncryption"
                              (auth/fetch-token-encryption-key base-url)))))))

(deftest encrypt-token-test
  (testing "plaintext is `{token}|{timestampMs}` UTF-8 bytes, result is base64"
    (let [captured (atom nil)]
      (with-redefs [crypto/rsa-oaep-sha256-encrypt
                    (fn [_pk ^bytes pt]
                      (reset! captured (String. pt "UTF-8"))
                      (byte-array [1 2 3 4]))]
        (is (= (crypto/base64-encode (byte-array [1 2 3 4]))
               (auth/encrypt-token ::fake-key "ref|ctx|secret" 1744659000000))))
      (is (= "ref|ctx|secret|1744659000000" @captured)
          "full pipe-separated token plus |timestamp is encrypted verbatim"))))

(deftest submit-ksef-token-test
  (testing "posts correct body shape and extracts nested authenticationToken.token"
    (let [calls (atom [])
          responses (atom {(str base-url "/auth/ksef-token")
                           (json-body {:referenceNumber "REF-42"
                                       :authenticationToken {:token "op-token-xyz"
                                                             :validUntil "2026-04-14T20:00:00Z"}})})]
      (with-redefs [http/post (stub-post responses calls)]
        (is (= {:reference-number "REF-42" :auth-token "op-token-xyz"}
               (auth/submit-ksef-token base-url
                                       {:challenge "chal"
                                        :nip 6423166047
                                        :encrypted-token "BASE64CIPHERTEXT"}))))
      (let [req (first @calls)
            body (:json req)]
        (is (= (str base-url "/auth/ksef-token") (:url req)))
        (is (= "chal" (:challenge body)))
        (is (= "BASE64CIPHERTEXT" (:encryptedToken body)))
        (testing "contextIdentifier.type is 'Nip' (capital N), value is a STRING"
          (is (= "Nip" (get-in body [:contextIdentifier :type])))
          (is (= "6423166047" (get-in body [:contextIdentifier :value]))))))))

(deftest poll-auth-status-happy-path-test
  (testing "polls until body.status.code == 200, sends Bearer header each call"
    (let [calls (atom [])
          responses (atom {(str base-url "/auth/REF-42")
                           (let [seq* (atom [(json-body {:status {:code 100 :description "In progress"}})
                                             (json-body {:status {:code 100 :description "In progress"}})
                                             (json-body {:status {:code 200 :description "OK"}
                                                         :authenticationToken {:token "op"}})])]
                             (fn [_opts]
                               (let [[h & rest] @seq*]
                                 (reset! seq* rest)
                                 h)))})]
      (with-redefs [http/get (stub-get responses calls)]
        (let [body (auth/poll-auth-status base-url "REF-42" "op-auth-token"
                                          :interval-ms 1
                                          :timeout-ms 5000)]
          (is (= 200 (get-in body [:status :code])))))
      (is (= 3 (count @calls)) "polled three times, last one succeeded")
      (doseq [c @calls]
        (is (= "Bearer op-auth-token" (get-in c [:opts :headers "Authorization"])))))))

(deftest poll-auth-status-failure-test
  (testing "terminal failure code (400) throws ex-info with status in data"
    (let [calls (atom [])
          responses (atom {(str base-url "/auth/REF-42")
                           (json-body {:status {:code 400
                                                :description "AuthenticationTokenUnauthorized"
                                                :details ["bad token"]}})})]
      (with-redefs [http/get (stub-get responses calls)]
        (try
          (auth/poll-auth-status base-url "REF-42" "bad-token"
                                 :interval-ms 1 :timeout-ms 5000)
          (is false "should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (re-find #"authentication failed" (.getMessage e)))
            (is (= 400 (get-in (ex-data e) [:status :code])))))))))

(deftest poll-auth-status-timeout-test
  (testing "exceeds timeout while still in-progress → throws timeout ex-info"
    (let [calls (atom [])
          responses (atom {(str base-url "/auth/REF-42")
                           (json-body {:status {:code 100 :description "In progress"}})})]
      (with-redefs [http/get (stub-get responses calls)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Timed out"
                              (auth/poll-auth-status base-url "REF-42" "op"
                                                     :interval-ms 1 :timeout-ms 5)))))))

(deftest redeem-tokens-test
  (testing "extracts both token strings and their validUntil from nested response"
    (let [calls (atom [])
          responses (atom {(str base-url "/auth/token/redeem")
                           (json-body {:accessToken  {:token "ACCESS-123"
                                                      :validUntil "2026-04-14T20:00:00Z"}
                                       :refreshToken {:token "REFRESH-456"
                                                      :validUntil "2026-04-21T20:00:00Z"}})})]
      (with-redefs [http/post (stub-post responses calls)]
        (is (= {:access-token "ACCESS-123"
                :refresh-token "REFRESH-456"
                :access-valid-until "2026-04-14T20:00:00Z"
                :refresh-valid-until "2026-04-21T20:00:00Z"}
               (auth/redeem-tokens base-url "op-token"))))
      (is (= "Bearer op-token"
             (get-in (first @calls) [:opts :headers "Authorization"]))
          "Bearer header carries the operation (authentication) token, not the access token"))))

(deftest authenticate-end-to-end-test
  (testing "full 5-step dance returns access/refresh tokens; all 5 HTTP calls happen in order"
    (let [calls (atom [])
          challenge-url (str base-url "/auth/challenge")
          certs-url    (str base-url "/security/public-key-certificates")
          ksef-url     (str base-url "/auth/ksef-token")
          status-url   (str base-url "/auth/REF-42")
          redeem-url   (str base-url "/auth/token/redeem")
          gets (atom {certs-url (json-body [{:usage ["KsefTokenEncryption"] :certificate "DER-OK"}])
                      status-url (json-body {:status {:code 200 :description "OK"}})})
          posts (atom {challenge-url (json-body {:challenge "C" :timestampMs 111})
                       ksef-url     (json-body {:referenceNumber "REF-42"
                                                :authenticationToken {:token "op-tok"}})
                       redeem-url   (json-body {:accessToken  {:token "A" :validUntil "u1"}
                                                :refreshToken {:token "R" :validUntil "u2"}})})]
      (with-redefs [http/get (stub-get gets calls)
                    http/post (stub-post posts calls)
                    crypto/parse-x509-cert-der (fn [_] ::fake-pk)
                    crypto/rsa-oaep-sha256-encrypt (fn [_ _] (byte-array [9 9 9]))]
        (is (= {:access-token "A"
                :refresh-token "R"
                :access-valid-until "u1"
                :refresh-valid-until "u2"}
               (auth/authenticate {:base-url base-url
                                   :nip 6423166047
                                   :token "ref|ctx|secret"
                                   :poll-interval-ms 1
                                   :poll-timeout-ms 5000}))))
      (let [urls (mapv :url @calls)]
        (is (= [challenge-url certs-url ksef-url status-url redeem-url] urls)
            "the 5 endpoints are hit in the documented order")))))
