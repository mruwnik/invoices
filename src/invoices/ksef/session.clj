(ns invoices.ksef.session
  "KSeF 2.0 online (interactive) session: open → send invoice → close → poll
  → fetch per-invoice status + UPO.

  Protocol source:
    - https://github.com/CIRFMF/ksef-docs/blob/main/sesja-interaktywna.md
    - https://github.com/CIRFMF/ksef-docs/blob/main/faktury/sesja-sprawdzenie-stanu-i-pobranie-upo.md
    - /workspace/ksef-docs/open-api.json

  The caller supplies an already-minted `:access-token` (from invoices.ksef.auth).
  This module is independent of auth — it just wraps the send flow.

  Several field-shape traps worth calling out (cross-check against open-api.json
  if anything looks off):

  * Session-status response (`GET /sessions/{ref}`) does NOT contain per-invoice
    `ksefNumber`. HTTP is always 200; terminal state is `body.status.code == 200`
    AND `upo.pages` non-empty. To get the `ksefNumber`, call
    `GET /sessions/{ref}/invoices/{invoiceRef}` afterwards.
  * `/sessions/online` returns **201** (not 200); the open action is idempotent
    per-token but not retry-safe.
  * `/sessions/online/{ref}/invoices` returns **202**; body is `{referenceNumber}`
    where that ref is the *invoice* reference (not the KSeF number — see above).
  * `/sessions/online/{ref}/close` returns **204 No Content** — no body.
  * The per-invoice UPO endpoint returns `application/xml`, NOT JSON. Fetch it
    with `:as :string` and do not parse as JSON.
  * `invoiceSize` and `encryptedInvoiceSize` must be **byte counts**, not char
    counts. FA(3) XML contains UTF-8 Polish characters that are multi-byte."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [invoices.ksef.crypto :as crypto]))

(def ^:private user-agent "invoices-clj/ksef")

(def schemas
  "Known form-code descriptors for the `/sessions/online` open request."
  {:fa-3 {:systemCode "FA (3)" :schemaVersion "1-0E" :value "FA"}})

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

(defn- raw-post [url opts]
  (http/post url (merge {:throw-exceptions true
                         :headers {"User-Agent" user-agent}}
                        opts)))

(defn- raw-get [url opts]
  (http/get url (merge {:as :string :throw-exceptions true
                        :headers {"User-Agent" user-agent}}
                       opts)))

(defn- bearer [token]
  {:headers {"Authorization" (str "Bearer " token)}})

(defn- merge-headers [a b]
  {:headers (merge (:headers a) (:headers b))})

(defn fetch-session-encryption-key
  "GET /security/public-key-certificates. Returns the parsed PublicKey of the
  first cert whose `usage` array contains `SymmetricKeyEncryption`."
  [base-url]
  (let [{body :body} (json-get (str base-url "/security/public-key-certificates") {})
        cert (->> body
                  (filter #(some #{"SymmetricKeyEncryption"} (:usage %)))
                  first)]
    (when-not cert
      (throw (ex-info "No SymmetricKeyEncryption cert in /security/public-key-certificates"
                      {:response body})))
    (crypto/parse-x509-cert-der (:certificate cert))))

(defn open-online-session
  "POST /sessions/online. Returns session `referenceNumber`."
  [base-url access-token schema-info encrypted-key-b64 iv-b64]
  (let [{body :body}
        (json-post (str base-url "/sessions/online")
                   {:formCode schema-info
                    :encryption {:encryptedSymmetricKey encrypted-key-b64
                                 :initializationVector iv-b64}}
                   (bearer access-token))]
    (:referenceNumber body)))

(defn send-invoice-to-session
  "POST /sessions/online/{sessionRef}/invoices. Returns invoice `referenceNumber`.
  Caller supplies pre-computed hashes and sizes (byte counts) of both the
  plaintext and ciphertext; this fn does not re-encrypt or re-hash."
  [base-url access-token session-ref
   {:keys [invoice-hash invoice-size encrypted-invoice-hash
           encrypted-invoice-size encrypted-invoice-content]}]
  (let [{body :body}
        (json-post (str base-url "/sessions/online/" session-ref "/invoices")
                   {:invoiceHash invoice-hash
                    :invoiceSize invoice-size
                    :encryptedInvoiceHash encrypted-invoice-hash
                    :encryptedInvoiceSize encrypted-invoice-size
                    :encryptedInvoiceContent encrypted-invoice-content}
                   (bearer access-token))]
    (:referenceNumber body)))

(defn close-session
  "POST /sessions/online/{sessionRef}/close. 204 No Content on success."
  [base-url access-token session-ref]
  (raw-post (str base-url "/sessions/online/" session-ref "/close")
            (bearer access-token))
  nil)

(defn fetch-session-status
  "GET /sessions/{sessionRef}. Returns parsed JSON body with :status, :upo, etc."
  [base-url access-token session-ref]
  (:body (json-get (str base-url "/sessions/" session-ref)
                   (bearer access-token))))

(def ^:private session-success-code 200)

(defn- session-terminal-failure? [code]
  (and code
       (not= code session-success-code)
       (not (<= 100 code 199))))

(defn- session-processed? [body]
  (and (= session-success-code (get-in body [:status :code]))
       (seq (get-in body [:upo :pages]))))

(defn- session-terminal? [body]
  (or (session-processed? body)
      (session-terminal-failure? (get-in body [:status :code]))))

(defn poll-session-processed
  "Poll `GET /sessions/{sessionRef}` every `interval-ms` until session reaches
  a terminal state, then return the final body. Two terminal shapes exist:

    * **Success**: `body.status.code == 200` AND `upo.pages` non-empty
      (UPO generation has finished). `session-processed?` returns true.
    * **Failure**: non-1xx non-200 `status.code` (e.g. 445 \"brak poprawnych
      faktur\" when every invoice in the session was rejected — including the
      duplicate-detection path). `session-processed?` returns false. Caller
      is expected to inspect the body and decide whether per-invoice fallback
      data is available (the `submit-invoice` flow reads
      `[:status :extensions :originalKsefNumber]` to recover the original
      ksefNumber on a 440 duplicate).

  Throws only on timeout — terminal failures are now part of the normal
  return shape so the duplicate-detection recovery path can run."
  [base-url access-token session-ref
   & {:keys [interval-ms timeout-ms]
      :or {interval-ms 2000 timeout-ms 120000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [body (fetch-session-status base-url access-token session-ref)]
        (cond
          (session-terminal? body) body
          (>= (System/currentTimeMillis) deadline)
          (throw (ex-info "Timed out waiting for KSeF session processing"
                          {:session-ref session-ref :last-status (:status body)}))
          :else (do (Thread/sleep interval-ms) (recur)))))))

(defn fetch-invoice-status
  "GET /sessions/{sessionRef}/invoices/{invoiceRef}. Returns the parsed JSON
  body — `:ksefNumber` is on successful entries; the duplicate-failure case
  carries the original ksefNumber under
  `[:status :extensions :originalKsefNumber]`."
  [base-url access-token session-ref invoice-ref]
  (:body (json-get (str base-url "/sessions/" session-ref
                        "/invoices/" invoice-ref)
                   (bearer access-token))))

(defn fetch-invoice-upo
  "GET /sessions/{sessionRef}/invoices/{invoiceRef}/upo. Returns the UPO as a
  raw XAdES-signed XML string. The endpoint serves `application/xml`, not JSON."
  [base-url access-token session-ref invoice-ref]
  (:body (raw-get (str base-url "/sessions/" session-ref
                       "/invoices/" invoice-ref "/upo")
                  (merge-headers (bearer access-token)
                                 {:headers {"Accept" "application/xml"}}))))

(defn- crc8-07
  "CRC-8 over UTF-8 bytes of `s` with polynomial 0x07 and init 0x00."
  ^long [^String s]
  (let [bs (.getBytes s "UTF-8")]
    (loop [i 0 crc 0]
      (if (= i (alength bs))
        crc
        (let [b (bit-and (aget bs i) 0xff)
              crc (loop [j 0 c (bit-xor crc b)]
                    (if (= j 8)
                      c
                      (recur (inc j)
                             (bit-and 0xff
                                      (if (zero? (bit-and c 0x80))
                                        (bit-shift-left c 1)
                                        (bit-xor (bit-shift-left c 1) 0x07))))))]
          (recur (inc i) crc))))))

(defn valid-ksef-number?
  "True iff `s` matches the KSeF number format `NIP-YYYYMMDD-12HEX-CRC8`
  (35 chars total) AND the trailing CRC-8 over the first 32 chars matches.

  Algorithm: CRC-8 with polynomial 0x07, init 0x00, applied to the ASCII bytes
  of the first 32 characters (everything up to but not including the final
  `-CRC`). The two-char hex checksum in the number must equal the computed CRC.

  Example: `5265877635-20250826-0100001AF629-AF` is valid."
  [s]
  (boolean
    (and (string? s)
         (= 35 (count s))
         (re-matches #"\d{10}-\d{8}-[0-9A-F]{12}-[0-9A-F]{2}" s)
         (let [data (subs s 0 32)
               expected (Long/parseLong (subs s 33 35) 16)]
           (= expected (crc8-07 data))))))

(defn- byte-count ^long [^bytes bs] (alength bs))

(defn- try-fetch-invoice-status
  "Wrap `fetch-invoice-status` so the fallback path in `submit-invoice`
  never swallows a working ksefNumber just because the per-invoice lookup
  itself blew up. Returns nil on any HTTP/parse error — but prints a
  breadcrumb so 'we silently dropped into no-fallback' shows up as 'we
  saw this specific exception and chose to continue' in incident logs."
  [base-url access-token session-ref invoice-ref]
  (try (fetch-invoice-status base-url access-token session-ref invoice-ref)
       (catch Throwable t
         (println (str "    - KSeF duplicate-fallback lookup failed: "
                       (.getMessage t)))
         nil)))

(defn submit-invoice
  "Run the full online-session send flow for a single invoice.

  `opts`:
    :base-url     — e.g. `\"https://api-test.ksef.mf.gov.pl/v2\"`
    :access-token — Bearer token from `invoices.ksef.auth/authenticate`
    :invoice-xml  — the FA(3) XML document as a string
    :schema       — `:fa-3` (default)
    :poll-interval-ms / :poll-timeout-ms — optional polling overrides

  Returns `{:ksef-number ..., :upo-xml ..., :invoice-ref ..., :session-ref ...}`.
  On the duplicate-detection path (session code 445 + per-invoice 440) the
  returned `:ksef-number` is the `originalKsefNumber` from the first submission
  and `:upo-xml` is nil — KSeF only serves a UPO for the original, not the
  duplicate. Throws ex-info on HTTP failure, malformed KSeF number, session
  timeout, or when the session failed AND no fallback ksefNumber was present."
  [{:keys [base-url access-token invoice-xml schema
           poll-interval-ms poll-timeout-ms]
    :or {schema :fa-3}}]
  (let [schema-info (or (get schemas schema)
                        (throw (ex-info "Unknown KSeF schema" {:schema schema})))
        public-key (fetch-session-encryption-key base-url)
        aes-key (crypto/generate-aes-key)
        iv (crypto/generate-iv)
        encrypted-key-b64 (crypto/base64-encode
                            (crypto/rsa-oaep-sha256-encrypt public-key (.getEncoded aes-key)))
        iv-b64 (crypto/base64-encode iv)
        session-ref (open-online-session base-url access-token schema-info
                                         encrypted-key-b64 iv-b64)
        plaintext-bytes (.getBytes ^String invoice-xml "UTF-8")
        ciphertext-bytes (crypto/aes-256-cbc-encrypt aes-key iv plaintext-bytes)
        invoice-ref (send-invoice-to-session
                      base-url access-token session-ref
                      {:invoice-hash (crypto/base64-encode (crypto/sha-256 plaintext-bytes))
                       :invoice-size (byte-count plaintext-bytes)
                       :encrypted-invoice-hash (crypto/base64-encode (crypto/sha-256 ciphertext-bytes))
                       :encrypted-invoice-size (byte-count ciphertext-bytes)
                       :encrypted-invoice-content (crypto/base64-encode ciphertext-bytes)})
        _ (close-session base-url access-token session-ref)
        session-body (poll-session-processed base-url access-token session-ref
                                             :interval-ms (or poll-interval-ms 2000)
                                             :timeout-ms (or poll-timeout-ms 120000))
        session-ok? (session-processed? session-body)
        invoice-status (if session-ok?
                         (fetch-invoice-status base-url access-token session-ref invoice-ref)
                         (try-fetch-invoice-status base-url access-token session-ref invoice-ref))
        ksef-number (or (:ksefNumber invoice-status)
                        (get-in invoice-status [:status :extensions :originalKsefNumber]))]
    (when-not ksef-number
      (throw (ex-info "KSeF session failed and no fallback ksefNumber available"
                      {:session-ref session-ref :invoice-ref invoice-ref
                       :session-status (:status session-body)
                       :invoice-status invoice-status})))
    (when-not (valid-ksef-number? ksef-number)
      (throw (ex-info "KSeF returned a malformed numer KSeF"
                      {:ksef-number ksef-number})))
    {:ksef-number ksef-number
     :upo-xml (when session-ok?
                (fetch-invoice-upo base-url access-token session-ref invoice-ref))
     :invoice-ref invoice-ref
     :session-ref session-ref}))
