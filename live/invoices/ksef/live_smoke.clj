(ns invoices.ksef.live-smoke
  "End-to-end live-smoke battery against the real KSeF sandbox(es).

  Run with:

      source /tmp/claude-10000/ksef-env.sh   # exports KSEF_{TEST,DEMO}_* creds
      clj -X:live

  Two environments are exercised:

   * TEST (api-test.ksef.mf.gov.pl) — protocol edge cases. The critical
     test here is duplicate-detection: we submit the same invoice twice
     and assert that (a) KSeF returns error 440 for the second one, and
     (b) our `session.clj` code correctly extracts the original
     `ksefNumber` from the duplicate response — the fallback path on
     `[:status :extensions :originalKsefNumber]` that Renarin flagged as
     a latent gap during the original session.clj review.

   * DEMO (api-demo.ksef.mf.gov.pl) — real-world flow coverage. One
     submission per flow category (non-EU :np, Polish mixed-rate,
     intra-EU :np-eu, Polish :zw). DEMO is tenant-isolated so we
     get a cleaner environment than TEST for flow sweeps.

  Self-skip behavior: if a given environment's credentials are missing
  (`KSEF_<ENV>_TOKEN`, `KSEF_<ENV>_NIP`, `KSEF_<ENV>_BASE`), that
  environment's battery is skipped with a single log line and the run
  proceeds with whatever is available. If BOTH environments lack
  credentials, the run exits with a `skipped` report — not a failure.

  The battery is deliberately kept out of `clj -X:test` (via its own
  `live/` source path) so the unit-test run stays fast and network-free.
  `clj -X:live` is the ONLY way to invoke it.

  Report format: stdout gets a structured block listing each flow with
  its status and captured ksefNumber. The same block is appended to
  `/home/claude/data/team-logs/ksef-integration.md` under a dated
  `### Live-smoke run <timestamp>` header so the team log retains the
  history.

  Failure-mode gotcha: a KSeF `status.code == 450` with details like
  `\"Token o numerze referencyjnym X nie został znaleziony\"` is NOT a
  code bug — KSeF parsed the pipe-separated token correctly and looked
  up segment 1 as the token reference, but the reference doesn't exist
  in the sandbox DB. Either the token was revoked, its minting is still
  settling after a KSeF maintenance window, or it was issued against a
  different env. Regenerate the token via the KSeF taxpayer panel and
  update `/tmp/claude-10000/ksef-env.sh`; don't start refactoring
  `invoices.ksef.auth`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [invoices.ksef.auth :as auth]
            [invoices.ksef.session :as session]
            [invoices.ksef.xml :as fa])
  (:import [java.time LocalDate ZonedDateTime ZoneOffset]
           [java.time.format DateTimeFormatter]))

;; ---------------- environment lookup ----------------

(defn- env [k]
  (let [v (System/getenv k)]
    (when (and v (not (str/blank? v))) v)))

(defn- env-config
  "Return `{:token :nip :base :label}` for the given env prefix, or nil
  if any of the three required variables is missing. The prefix is the
  uppercase environment short name (`\"TEST\"` or `\"DEMO\"`)."
  [prefix]
  (let [token (env (str "KSEF_" prefix "_TOKEN"))
        nip   (env (str "KSEF_" prefix "_NIP"))
        base  (env (str "KSEF_" prefix "_BASE"))]
    (when (and token nip base)
      {:token token :nip nip :base base :label prefix})))

;; ---------------- invoice fixtures ----------------

(def ^:private seller
  {:name    "Mr. Blobby Live-Smoke"
   :address "ul. Podwodna 1, 12-345 Mierzow"})

(defn- simple-item [vat title netto]
  {:vat vat :netto netto :title title})

(defn- mk-invoice
  "Build a minimal invoice map suitable for FA(3) submission. Takes the
  seller NIP, a unique invoice number, a buyer map, and a vector of
  items. Date is always today — KSeF rejects future dates on P_1 and
  yesterday's date is fine too but today is simplest."
  [nip number buyer items]
  {:seller (assoc seller :nip nip)
   :buyer  buyer
   :number number
   :date   (LocalDate/now)
   :items  items})

(defn- unique-number [tag]
  (str "SMOKE-" tag "-" (System/currentTimeMillis)))

;; Buyer fixtures keyed by the kind of flow we want to exercise.

(defn- pl-buyer [nip]
  {:name "Buty S.A." :address "ul. Szewska 32, 76-543 Bakow" :nip nip})

(def ^:private us-buyer
  {:name "Acme Inc."
   :address "1 Infinite Loop, Cupertino CA 95014"
   :country "US"
   :nip "US-TAX-ACME"})

(def ^:private de-buyer
  {:name "Beispiel GmbH"
   :address "Hauptstrasse 1, 10115 Berlin"
   :country "DE"
   :nip "DE123456789"})

;; ---------------- submission primitive ----------------

(defn- run-flow
  "Submit a single invoice to KSeF and return a result map:
    {:name <string> :ok? <bool> :ksef-number <string|nil> :error <string|nil>
     :elapsed-ms <long>}
  Exceptions are captured and recorded, never propagated — a single
  broken flow must not abort the rest of the battery."
  [flow-name base-url access-token invoice]
  (println (format "  [live-smoke] %s: submitting %s" flow-name (:number invoice)))
  (let [start (System/currentTimeMillis)]
    (try
      (let [xml (fa/invoice->fa3-xml invoice)
            {:keys [ksef-number]} (session/submit-invoice
                                    {:base-url base-url
                                     :access-token access-token
                                     :invoice-xml xml
                                     :schema :fa-3})
            elapsed (- (System/currentTimeMillis) start)]
        (println (format "  [live-smoke] %s: OK ksefNumber=%s (%d ms)"
                         flow-name ksef-number elapsed))
        {:name flow-name :ok? true :ksef-number ksef-number
         :error nil :elapsed-ms elapsed})
      (catch Throwable t
        (let [elapsed (- (System/currentTimeMillis) start)
              data    (ex-data t)
              msg     (-> (str (.getMessage t)
                               (when data (str " — ex-data: " (pr-str data))))
                          (str/replace #"\s+" " "))]
          (println (format "  [live-smoke] %s: FAIL %s (%d ms)"
                           flow-name msg elapsed))
          {:name flow-name :ok? false :ksef-number nil
           :error msg :elapsed-ms elapsed})))))

(defn- authenticate-or-throw [{:keys [base token nip label]}]
  (println (format "  [live-smoke] %s: authenticating against %s as NIP %s"
                   label base nip))
  (:access-token (auth/authenticate {:base-url base :nip nip :token token})))

(defn- env-level-failure
  "Build a single synthetic result row representing an env-wide crash
  (typically an auth failure). Used when `run-*-battery` throws before
  reaching its individual `run-flow` calls — the stacktrace gets caught
  one level up and surfaces here as a structured result so the report
  and exit-code path still work."
  [label ^Throwable t]
  [{:name (str label "-battery") :ok? false :ksef-number nil
    :error (str "env-level failure: " (.getMessage t))
    :elapsed-ms 0}])

(defn- safe-battery [label battery-fn cfg]
  (try (battery-fn cfg)
       (catch Throwable t
         (println (format "  [live-smoke] %s: env-level failure: %s"
                          label (.getMessage t)))
         (env-level-failure label t))))

;; ---------------- TEST battery ----------------

(defn- run-duplicate-detection-flow
  "Submit the SAME invoice twice in quick succession. Expect:
    * 1st submission: fresh ksefNumber.
    * 2nd submission: either succeeds with the SAME ksefNumber via the
      `originalKsefNumber` fallback path in `submit-invoice` (the
      supported behavior), or surfaces an ex-info describing the 440.

  The captured result encodes both paths so the report makes it obvious
  which branch the sandbox exercised. The invoice number is locked to a
  run-local constant so the two submissions collide deterministically."
  [base-url access-token nip]
  (let [number (unique-number "DUP")
        invoice (mk-invoice nip number (pl-buyer nip)
                            [(simple-item 23 "Duplicate-detection widget" 100)])
        first-result (run-flow "duplicate-detection-1st" base-url access-token invoice)]
    (if-not (:ok? first-result)
      [first-result {:name "duplicate-detection-2nd" :ok? false
                     :ksef-number nil :elapsed-ms 0
                     :error "skipped: 1st submission failed"}]
      (let [first-ksef (:ksef-number first-result)
            second-result (run-flow "duplicate-detection-2nd" base-url access-token invoice)
            same-number? (and (:ksef-number second-result)
                              (= first-ksef (:ksef-number second-result)))
            result (cond
                     same-number?
                     (assoc second-result
                            :notes (str "matched original ksefNumber via 440 fallback: " first-ksef))

                     (:ok? second-result)
                     (assoc second-result
                            :ok? false
                            :error (str "expected duplicate but got new ksefNumber "
                                        (:ksef-number second-result)
                                        " (first was " first-ksef ")"))

                     :else
                     (assoc second-result
                            :notes (str "first ksefNumber was " first-ksef
                                        "; 2nd surfaced as ex-info (fallback path blocked)")))]
        [first-result result]))))

(defn- run-test-battery
  "TEST sandbox battery: protocol edge cases. Returns a vector of
  result maps in run order."
  [{:keys [base] :as cfg}]
  (println (format "  [live-smoke] TEST battery start: %s" base))
  (let [access-token (authenticate-or-throw cfg)
        nip (:nip cfg)
        sanity-invoice (mk-invoice nip (unique-number "TEST-SANITY") (pl-buyer nip)
                                   [(simple-item 23 "Sanity-check widget" 50)])
        sanity (run-flow "test-sanity-23%" base access-token sanity-invoice)
        [dup-1 dup-2] (run-duplicate-detection-flow base access-token nip)]
    [sanity dup-1 dup-2]))

;; ---------------- DEMO battery ----------------

(def ^:private demo-flows
  "Each entry names one real-world flow category and an items/buyer
  recipe for building an invoice on the fly. The recipe needs the seller
  nip (for the PL buyer in the mixed/zw flows) so we defer construction
  until run time."
  [{:name  "demo-nonEU-np"
    :build (fn [nip]
             ;; 1-3 items mirroring real monthly production shape (one item per
             ;; month after `invoices.time/date-applies?` filtering), with a
             ;; mixed :currency set so `warn-item-currency-mismatch!` fires at
             ;; least once per run. The USD line exercises the warn-and-ignore
             ;; path; the invoice's KSeF currency is always derived from the
             ;; seller's default (PLN) regardless.
             {:buyer us-buyer
              :items [(assoc (simple-item :np "Software development — sprint 42" 5000)
                             :currency "PLN")
                      (assoc (simple-item :np "Third-party tooling — chargeback" 120)
                             :currency "USD")]})}
   {:name  "demo-polish-mixed"
    :build (fn [nip]
             {:buyer (pl-buyer nip)
              :items [(simple-item 23 "Konsultacje IT" 1000)
                      (simple-item 8  "Remont biura"   500)
                      (simple-item 5  "Materialy biurowe" 200)]})}
   {:name  "demo-EU-np-eu"
    :build (fn [nip]
             {:buyer de-buyer
              :items [(simple-item :np-eu "Consulting — art. 100 ust. 1 pkt 4" 3000)]})}
   {:name  "demo-polish-zw"
    :build (fn [nip]
             {:buyer (pl-buyer nip)
              :items [(assoc (simple-item :zw "Uslugi zwolnione z VAT" 400)
                             :notes ["Podstawa zwolnienia z VAT: art. 113 ust. 1 i 9 Ustawa o VAT"])]})}
   {:name  "demo-polish-simple-23"
    :build (fn [nip]
             {:buyer (pl-buyer nip)
              :items [(simple-item 23 "Single-line sanity check" 250)]})}])

(defn- run-demo-battery
  "DEMO sandbox battery: one submission per flow category."
  [{:keys [base] :as cfg}]
  (println (format "  [live-smoke] DEMO battery start: %s" base))
  (let [access-token (authenticate-or-throw cfg)
        nip (:nip cfg)]
    (vec
      (for [{:keys [name build]} demo-flows]
        (let [{:keys [buyer items]} (build nip)
              invoice (mk-invoice nip (unique-number name) buyer items)]
          (run-flow name base access-token invoice))))))

;; ---------------- report assembly ----------------

(defn- result-line [{:keys [name ok? ksef-number error notes elapsed-ms]}]
  (let [status (if ok? "OK  " "FAIL")
        ksef   (or ksef-number "-")
        trail  (cond
                 notes (str "  ; " notes)
                 error (str "  ; err: " error)
                 :else "")]
    (format "  %s  %-28s  %-38s  (%d ms)%s"
            status name ksef elapsed-ms trail)))

(defn- section-lines [label results]
  (if (empty? results)
    [(format "## %s — skipped (creds missing)" label)]
    (into [(format "## %s" label)] (map result-line results))))

(defn- now-utc-str []
  (-> (ZonedDateTime/now ZoneOffset/UTC)
      (.format DateTimeFormatter/ISO_INSTANT)))

(defn- build-report [test-results demo-results]
  (let [header (format "### Live-smoke run %s" (now-utc-str))
        all    (concat test-results demo-results)
        total  (count all)
        failed (count (remove :ok? all))
        summary (if (pos? total)
                  (format "Summary: %d flows, %d failed" total failed)
                  "Summary: no flows executed (both environments lacked credentials)")]
    (str/join "\n"
      (concat [header summary ""]
              (section-lines "TEST" test-results)
              [""]
              (section-lines "DEMO" demo-results)
              [""]))))

(defn- append-to-team-log [report]
  (let [path "/home/claude/data/team-logs/ksef-integration.md"]
    (try
      (with-open [w (io/writer (io/file path) :append true)]
        (.write w "\n")
        (.write w report))
      (println (str "  [live-smoke] appended report to " path))
      (catch Throwable t
        (println (str "  [live-smoke] WARN: failed to append to team log: "
                      (.getMessage t)))))))

;; ---------------- entry point ----------------

(defn- exit-code [test-results demo-results]
  (if (every? :ok? (concat test-results demo-results)) 0 1))

(defn run
  "Main entry point invoked as `clj -X:live`. Arg map is ignored — all
  configuration comes from environment variables. Returns nothing; exits
  the JVM with status 0 if every executed flow passed, 1 otherwise.
  A skipped environment does NOT count as failure."
  [_]
  (println (format "[live-smoke] start %s" (now-utc-str)))
  (let [test-cfg (env-config "TEST")
        demo-cfg (env-config "DEMO")
        _ (when-not test-cfg
            (println "  [live-smoke] TEST skipped: KSEF_TEST_{TOKEN,NIP,BASE} not all set"))
        _ (when-not demo-cfg
            (println "  [live-smoke] DEMO skipped: KSEF_DEMO_{TOKEN,NIP,BASE} not all set"))
        test-results (if test-cfg (safe-battery "TEST" run-test-battery test-cfg) [])
        demo-results (if demo-cfg (safe-battery "DEMO" run-demo-battery demo-cfg) [])
        report (build-report test-results demo-results)]
    (println)
    (println report)
    (append-to-team-log report)
    (println (format "[live-smoke] done %s" (now-utc-str)))
    (System/exit (exit-code test-results demo-results))))

(defn -main [& _]
  (run nil))
