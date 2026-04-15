(ns invoices.ksef.auth
  "KSeF 2.0 token-based authentication.

  Implements the 5-step dance from
  https://github.com/CIRFMF/ksef-docs/blob/main/uwierzytelnianie.md#22-uwierzytelnianie-tokenem-ksef
  :

    1. POST /auth/challenge                       → challenge + timestampMs
    2. GET  /security/public-key-certificates     → pick the KsefTokenEncryption cert
    3. encrypt `\"{token}|{timestampMs}\"` with RSA-OAEP-SHA256 under that cert
    4. POST /auth/ksef-token                      → referenceNumber + authenticationToken
    5. GET  /auth/{referenceNumber} (Bearer authenticationToken) — poll until success
    6. POST /auth/token/redeem (Bearer authenticationToken)      → access + refresh tokens

  The user's test token has format `ref|context|secret`. Per the task decision,
  we pass the full pipe-separated string through as the ksefToken — i.e. we append
  `|{timestampMs}` and encrypt the whole thing. The pipe inside the token is ambiguous
  only on the receiving end; if the API rejects it, the integration test will surface
  it and the wiring task can adjust.

  The authentication token, access token, and refresh token are NEVER written to disk."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [invoices.ksef.crypto :as crypto]))

(def ^:private user-agent "invoices-clj/ksef")

(defn- json-get [url opts]
  (http/get url (merge {:as :json-strict :accept :json :throw-exceptions true
                        :headers {"User-Agent" user-agent}}
                       opts)))

(defn- json-post [url body opts]
  (http/post url (merge {:content-type :json
                         :accept :json
                         :as :json-strict
                         :throw-exceptions true
                         :headers {"User-Agent" user-agent}
                         :body (json/generate-string body)}
                        opts)))

(defn- bearer [token]
  {:headers {"Authorization" (str "Bearer " token)}})

(defn fetch-challenge
  "POST /auth/challenge. Returns {:challenge <str> :timestamp-ms <long>}.
  The sandbox returns both an ISO8601 `timestamp` and a numeric `timestampMs`
  — we use the numeric one verbatim."
  [base-url]
  (let [{body :body} (json-post (str base-url "/auth/challenge") {} {})]
    {:challenge (:challenge body)
     :timestamp-ms (long (:timestampMs body))}))

(defn fetch-token-encryption-key
  "GET /security/public-key-certificates, pick the cert whose `usage` array
  contains `KsefTokenEncryption`, parse it, and return the PublicKey."
  [base-url]
  (let [{body :body} (json-get (str base-url "/security/public-key-certificates") {})
        cert (->> body
                  (filter #(some #{"KsefTokenEncryption"} (:usage %)))
                  first)]
    (when-not cert
      (throw (ex-info "No KsefTokenEncryption cert in /security/public-key-certificates"
                      {:response body})))
    (crypto/parse-x509-cert-der (:certificate cert))))

(defn encrypt-token
  "Build the `{token}|{timestampMs}` plaintext, RSA-OAEP-SHA256 encrypt it
  under `public-key`, return base64."
  [public-key token timestamp-ms]
  (let [plaintext (.getBytes (str token "|" timestamp-ms) "UTF-8")]
    (crypto/base64-encode (crypto/rsa-oaep-sha256-encrypt public-key plaintext))))

(defn submit-ksef-token
  "POST /auth/ksef-token. Returns {:reference-number <str> :auth-token <str>}.
  `auth-token` is the short-lived operation token used to poll status and
  redeem access tokens — not the accessToken itself."
  [base-url {:keys [challenge nip encrypted-token]}]
  (let [{body :body} (json-post (str base-url "/auth/ksef-token")
                                {:challenge challenge
                                 :contextIdentifier {:type "Nip" :value (str nip)}
                                 :encryptedToken encrypted-token}
                                {})]
    {:reference-number (:referenceNumber body)
     :auth-token (get-in body [:authenticationToken :token])}))

(def ^:private success-code 200)

(defn- terminal-failure? [code]
  (and code (not= code success-code) (not= code 100)))

(defn poll-auth-status
  "GET /auth/{referenceNumber} every `interval-ms` until status.code is 200
  (success), or a non-100 non-200 code (failure), or the deadline is hit.
  Throws on failure or timeout.

  Also fails fast when the poll response has no `[:status :code]` at all:
  previously a malformed or empty body would fall through to the `:else`
  branch and silently re-poll until timeout, producing a confusing
  'timeout' error for what is actually an API-contract violation. We'd
  rather the incident log say 'auth response missing status.code' than
  'auth timed out' in that case."
  [base-url reference-number auth-token
   & {:keys [interval-ms timeout-ms]
      :or {interval-ms 1000 timeout-ms 60000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)
        url (str base-url "/auth/" reference-number)]
    (loop []
      (let [{body :body} (json-get url (bearer auth-token))
            code (get-in body [:status :code])]
        (cond
          (= code success-code) body
          (nil? code) (throw (ex-info "KSeF auth response missing status.code"
                                      {:reference-number reference-number
                                       :body body}))
          (terminal-failure? code) (throw (ex-info "KSeF authentication failed"
                                                   {:status (:status body)}))
          (>= (System/currentTimeMillis) deadline)
          (throw (ex-info "Timed out waiting for KSeF auth status"
                          {:reference-number reference-number :last-status (:status body)}))
          :else (do (Thread/sleep interval-ms) (recur)))))))

(defn redeem-tokens
  "POST /auth/token/redeem. Single-use: each call with the same `auth-token`
  after a successful redemption will 400. Returns {:access-token, :refresh-token}."
  [base-url auth-token]
  (let [{body :body} (json-post (str base-url "/auth/token/redeem") {} (bearer auth-token))]
    {:access-token (get-in body [:accessToken :token])
     :refresh-token (get-in body [:refreshToken :token])
     :access-valid-until (get-in body [:accessToken :validUntil])
     :refresh-valid-until (get-in body [:refreshToken :validUntil])}))

(defn authenticate
  "Run the full 5-step KSeF token auth dance. Returns
  `{:access-token, :refresh-token, :access-valid-until, :refresh-valid-until}`.

  `opts` is a map with:
    :base-url — e.g. `\"https://api-test.ksef.mf.gov.pl/v2\"` (no trailing slash)
    :nip      — seller NIP (string or number)
    :token    — the KSeF secret (never logged, never written to disk)
    :poll-interval-ms / :poll-timeout-ms — optional overrides for the status polling loop"
  [{:keys [base-url nip token poll-interval-ms poll-timeout-ms]}]
  (let [{:keys [challenge timestamp-ms]} (fetch-challenge base-url)
        public-key (fetch-token-encryption-key base-url)
        encrypted-token (encrypt-token public-key token timestamp-ms)
        {:keys [reference-number auth-token]}
        (submit-ksef-token base-url {:challenge challenge
                                     :nip nip
                                     :encrypted-token encrypted-token})]
    (poll-auth-status base-url reference-number auth-token
                      :interval-ms (or poll-interval-ms 1000)
                      :timeout-ms (or poll-timeout-ms 60000))
    (redeem-tokens base-url auth-token)))
