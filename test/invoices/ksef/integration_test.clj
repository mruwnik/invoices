(ns invoices.ksef.integration-test
  "End-to-end integration test against the real KSeF TEST sandbox
  (api-test.ksef.mf.gov.pl). This is the proof that the auth + session + xml
  pieces actually cooperate over TLS against Ministry of Finance infrastructure,
  not just against our clj-http mocks.

  Running locally:

      source /tmp/claude-10000/ksef-env.sh   # exports KSEF_TEST_TOKEN etc.
      clojure -M:test                        # runs everything including this ns
      # or, this ns only:
      clojure -Sdeps '{:paths [\"src\" \"test\" \"resources\"]}' -M \\
        -e '(require (quote invoices.ksef.integration-test))
            (clojure.test/run-tests (quote invoices.ksef.integration-test))'

  When `KSEF_TEST_TOKEN` is absent the test self-skips so contributors without
  sandbox credentials still see `clj -M:test` pass green. NEVER commit the token
  — it's a bearer credential.

  Gotchas to keep in mind when a run fails:

  * The sandbox has a daily maintenance window at 16:00–18:00 Europe/Warsaw.
    HTTP 5xx or connection resets during that window are environmental, not
    code bugs — wait and retry.
  * The TEST environment does not isolate tenants: the same NIP can be used by
    many integrators concurrently. Tests MUST NOT rely on persistent state
    across runs, and MUST NOT assume the NIP's state is ours to own.
  * 4xx during non-maintenance is a real bug — but **KSeF status code 450
    with `details: [\"Token o numerze referencyjnym X nie został znaleziony\"]`
    is a credential problem, not a code bug**. It means KSeF parsed the
    pipe-separated token correctly and looked up segment 1 as the token
    reference, but that reference doesn't exist in the sandbox DB. Either
    the token was revoked, its minting is still settling after a maintenance
    window, or it was issued against a different env. Empirical check done
    2026-04-14: sending only segment 3 (the hex secret) or only segment 1
    (the ref) or only segment 2 (the NIP context) produces a *different*
    error — `\"Invalid token encoding.\"` — which confirms the full
    pipe-separated string IS the correct wire format, and segments alone
    are not accepted. If you hit 450 \"not found\", regenerate the token via
    the KSeF taxpayer panel and update /tmp/claude-10000/ksef-env.sh; don't
    start refactoring the auth module.
  * Other real 4xx bugs are most likely schema drift between open-api.json
    and what the wire actually accepts — compare your request body against
    the latest open-api.json before blaming our code."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [invoices.ksef.auth :as auth]
            [invoices.ksef.session :as session]
            [invoices.ksef.xml :as fa])
  (:import [java.time LocalDate]))

(defn- env [k]
  (let [v (System/getenv k)]
    (when (and v (not (str/blank? v))) v)))

(def ^:private transient-http-statuses #{408 502 503 504})

(defn- transient?
  "A request failure is worth retrying if it's a TCP-level exception (timeout,
  reset, etc.) or an HTTP 5xx / 408. 4xx is never retried — those are policy
  errors, not infrastructure."
  [^Throwable t]
  (let [data (ex-data t)
        status (:status data)]
    (cond
      (contains? transient-http-statuses status) true
      status false
      :else (or (instance? java.net.SocketTimeoutException t)
                (instance? java.net.ConnectException t)
                (instance? java.io.IOException t)))))

(defn- retry-transient
  "Run `f` up to `max-attempts` times, retrying only on `transient?` errors.
  Re-throws the final exception on exhaustion."
  [max-attempts f]
  (loop [attempt 1]
    (let [[ok result]
          (try [true (f)]
               (catch Throwable t
                 (if (and (< attempt max-attempts) (transient? t))
                   [false t]
                   (throw t))))]
      (if ok
        result
        (do (println (format "  [integration] attempt %d failed (transient), retrying…" attempt))
            (Thread/sleep 2000)
            (recur (inc attempt)))))))

(def sample-seller
  {:name    "Mr. Blobby"
   :address "ul. Podwodna 1, 12-345 Mierzow"
   :nip     6423166047})

(def sample-buyer
  {:name    "Buty S.A."
   :address "ul. Szewska 32, 76-543 Bakow"
   :nip     6423166047})

(defn- build-sample-invoice
  "Construct a minimal one-item invoice map for sandbox submission. TEST env
  doesn't isolate tenants, so seller and buyer share the same NIP — we just
  need SOMETHING that validates against FA(3) and gets a ksefNumber back."
  [nip]
  {:seller (assoc sample-seller :nip nip)
   :buyer  (assoc sample-buyer  :nip nip)
   :number (str "INT-" (System/currentTimeMillis))
   :date   (LocalDate/now)
   :items  [{:vat 23 :netto 100 :title "Integration-test widget"}]})

(deftest ^{:integration true} ksef-sandbox-e2e-test
  (testing "Full KSeF TEST sandbox round-trip: authenticate → submit → UPO"
    (let [token (env "KSEF_TEST_TOKEN")
          nip   (env "KSEF_TEST_NIP")
          base  (env "KSEF_TEST_BASE")]
      (if-not (and token nip base)
        (println "  [integration] KSEF_TEST_TOKEN/NIP/BASE not set — skipping sandbox e2e")
        (let [_ (println "  [integration] running KSeF e2e against" base "as NIP" nip)
              auth-result
              (retry-transient 2
                #(auth/authenticate {:base-url base :nip nip :token token}))
              access-token (:access-token auth-result)]
          (is (string? access-token) "auth returns a string access-token")
          (is (seq access-token)      "access-token is non-empty")
          (let [invoice-map (build-sample-invoice nip)
                xml         (fa/invoice->fa3-xml invoice-map)]
            (is (string? xml))
            (is (str/includes? xml "Faktura"))
            (let [{:keys [ksef-number upo-xml invoice-ref session-ref]}
                  (retry-transient 2
                    #(session/submit-invoice {:base-url base
                                              :access-token access-token
                                              :invoice-xml xml
                                              :schema :fa-3}))]
              (println (format "  [integration] ksefNumber=%s  session-ref=%s  invoice-ref=%s"
                               ksef-number session-ref invoice-ref))
              (is (session/valid-ksef-number? ksef-number)
                  (str "ksefNumber format: " ksef-number))
              (is (string? upo-xml))
              (is (pos? (count (or upo-xml "")))
                  "UPO XML is non-empty")
              (is (str/includes? (or upo-xml "") "<")
                  "UPO looks like XML"))))))))
