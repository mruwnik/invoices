(ns invoices.ksef-test
  "Unit tests for invoices.ksef (the submit-to-ksef façade).

  Mocks every underlying submodule (auth, session, xml) so the tests can
  assert wiring and error-handling without touching crypto or HTTP. Sidecar
  I/O is exercised against real files under a tmp dir."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [invoices.ksef :as ksef]
            [invoices.ksef.auth :as auth]
            [invoices.ksef.session :as session]
            [invoices.ksef.xml :as xml]))

(def ^:private tmp-root "/tmp/claude-10000")

(defn- tmp-pdf
  "Write an empty placeholder PDF to a fresh path under tmp-root and return the File."
  [^String stem]
  (let [dir (io/file tmp-root "ksef-wiring-tests")
        _ (.mkdirs dir)
        f (io/file dir (str stem ".pdf"))]
    (spit f "%PDF-1.4\n% placeholder\n")
    f))

(defn- with-captured-out [f]
  (let [sw (java.io.StringWriter.)]
    (binding [*out* sw] (f))
    (str sw)))

(defn- stub-getenv [values]
  (fn [k] (get values k)))

(def ^:private base-invoice
  {:seller {:name "Mr. Blobby" :nip 1234567890}
   :buyer  {:name "Buty S.A." :nip 9875645342}
   :items  [{:vat 23 :netto 100 :title "t"}]
   :date   (java.time.LocalDate/parse "2026-04-14")
   :number "1/4/2026"})

(def ^:private ksef-meta
  {:env :test :nip 1234567890 :token-env "KSEF_TEST" :schema :fa-3})

(deftest submit-to-ksef-happy-path
  (testing "invocations flow through auth → session with correct args; sidecars written"
    (let [pdf (tmp-pdf "happy")
          calls (atom [])
          invoice-xml "<Faktura>fake</Faktura>"
          upo-xml "<UPO>fake</UPO>"
          authenticate-args (atom nil)
          submit-args (atom nil)]
      (with-redefs [xml/invoice->fa3-xml
                    (fn [inv] (swap! calls conj :xml)
                      (is (= "1/4/2026" (:number inv))
                          "xml generator sees the same assembled invoice")
                      (is (= (java.time.LocalDate/parse "2026-04-14") (:date inv)))
                      invoice-xml)

                    auth/authenticate
                    (fn [args] (swap! calls conj :auth)
                      (reset! authenticate-args args)
                      {:access-token "ACC-123" :refresh-token "REF-456"})

                    session/submit-invoice
                    (fn [args] (swap! calls conj :session)
                      (reset! submit-args args)
                      {:ksef-number "1234567890-20260414-0100001AF629-AF"
                       :upo-xml upo-xml
                       :invoice-ref "INV-1"
                       :session-ref "SES-1"})

                    ksef/getenv (stub-getenv {"KSEF_TEST" "ref|ctx|secret"})]
        (let [output (with-captured-out
                       #(ksef/submit-to-ksef (assoc base-invoice :ksef ksef-meta) pdf))]
          (is (re-find #"KSeF accepted: 1234567890-20260414-0100001AF629-AF" output))))

      (is (= [:auth :xml :session] @calls) "chain executes auth→xml→session (auth first so we don't waste XML gen on a rejected token)")
      (is (= {:base-url "https://api-test.ksef.mf.gov.pl/v2"
              :nip 1234567890
              :token "ref|ctx|secret"}
             @authenticate-args))
      (is (= {:base-url "https://api-test.ksef.mf.gov.pl/v2"
              :access-token "ACC-123"
              :invoice-xml invoice-xml
              :schema :fa-3}
             @submit-args))

      (testing "sidecars land next to the PDF with the invoice-xml and upo-xml"
        (let [stem (str/replace (.getPath pdf) #"\.pdf$" "")]
          (is (= invoice-xml (slurp (str stem ".ksef.xml"))))
          (is (= upo-xml (slurp (str stem ".upo.xml")))))))))

(deftest submit-to-ksef-missing-env-var
  (testing "env var unset → skip with a log line, return nil, no chain calls"
    (let [pdf (tmp-pdf "no-env")
          calls (atom [])]
      (with-redefs [xml/invoice->fa3-xml (fn [_] (swap! calls conj :xml) "<x/>")
                    auth/authenticate    (fn [_] (swap! calls conj :auth) {})
                    session/submit-invoice (fn [_] (swap! calls conj :session) {})
                    ksef/getenv          (stub-getenv {})]
        (let [output (with-captured-out
                       #(is (nil? (ksef/submit-to-ksef
                                    (assoc base-invoice :ksef ksef-meta) pdf))))]
          (is (re-find #"KSeF submission skipped: env var KSEF_TEST not set" output))))
      (is (empty? @calls) "no ksef chain calls when the token env var is unset"))))

(deftest submit-to-ksef-literal-token-test
  (testing "literal `:token` string in config is used directly — no env-var
            lookup, no indirection, the token reaches auth/authenticate as-is"
    (let [pdf (tmp-pdf "literal-token")
          captured (atom nil)
          ksef-literal (-> ksef-meta (dissoc :token-env) (assoc :token "LITERAL-TOK"))]
      (with-redefs [xml/invoice->fa3-xml (fn [_] "<x/>")
                    auth/authenticate    (fn [opts] (reset! captured (:token opts)) {:access-token "A"})
                    session/submit-invoice (fn [_] {:ksef-number "1234567890-20260414-0100001AF629-AF"
                                                    :upo-xml "<UPO/>"})
                    ksef/getenv          (stub-getenv {})]
        (with-captured-out
          #(ksef/submit-to-ksef (assoc base-invoice :ksef ksef-literal) pdf)))
      (is (= "LITERAL-TOK" @captured)
          "auth/authenticate received the literal :token directly"))))

(deftest submit-to-ksef-token-env-wins-over-literal-test
  (testing "when BOTH :token-env and :token are set AND the env var resolves,
            the env var value wins (runtime override semantics preserved)"
    (let [pdf (tmp-pdf "env-wins")
          captured (atom nil)
          ksef-both (assoc ksef-meta :token "LITERAL-LOSER")]
      (with-redefs [xml/invoice->fa3-xml (fn [_] "<x/>")
                    auth/authenticate    (fn [opts] (reset! captured (:token opts)) {:access-token "A"})
                    session/submit-invoice (fn [_] {:ksef-number "1234567890-20260414-0100001AF629-AF"
                                                    :upo-xml "<UPO/>"})
                    ksef/getenv          (stub-getenv {"KSEF_TEST" "ENV-WINNER"})]
        (with-captured-out
          #(ksef/submit-to-ksef (assoc base-invoice :ksef ksef-both) pdf)))
      (is (= "ENV-WINNER" @captured)
          ":token-env resolved to a non-blank value → wins over :token literal"))))

(deftest submit-to-ksef-literal-fallback-when-env-unset-test
  (testing "both set but env var unset → literal :token is used as fallback
            (operator didn't export the override this run, fall back to literal)"
    (let [pdf (tmp-pdf "env-unset")
          captured (atom nil)
          ksef-both (assoc ksef-meta :token "LITERAL-FALLBACK")]
      (with-redefs [xml/invoice->fa3-xml (fn [_] "<x/>")
                    auth/authenticate    (fn [opts] (reset! captured (:token opts)) {:access-token "A"})
                    session/submit-invoice (fn [_] {:ksef-number "1234567890-20260414-0100001AF629-AF"
                                                    :upo-xml "<UPO/>"})
                    ksef/getenv          (stub-getenv {})]
        (with-captured-out
          #(ksef/submit-to-ksef (assoc base-invoice :ksef ksef-both) pdf)))
      (is (= "LITERAL-FALLBACK" @captured)
          "env var blank → fall back to literal :token"))))

(deftest submit-to-ksef-no-token-at-all-test
  (testing "neither :token nor :token-env set → skip with a descriptive reason"
    (let [pdf (tmp-pdf "no-token-at-all")
          ksef-neither (dissoc ksef-meta :token-env)]
      (with-redefs [xml/invoice->fa3-xml (fn [_] (throw (Exception. "should not reach xml")))
                    auth/authenticate    (fn [_] (throw (Exception. "should not reach auth")))
                    session/submit-invoice (fn [_] (throw (Exception. "should not reach session")))
                    ksef/getenv          (stub-getenv {})]
        (let [output (with-captured-out
                       #(is (nil? (ksef/submit-to-ksef
                                    (assoc base-invoice :ksef ksef-neither) pdf))))]
          (is (re-find #"KSeF submission skipped:.*:token / :token-env missing" output)))))))

(deftest submit-to-ksef-auth-throws-is-caught
  (testing "auth failure is logged and returns nil; no sidecars written"
    (let [pdf (tmp-pdf "auth-boom")
          session-called (atom false)]
      (with-redefs [xml/invoice->fa3-xml (fn [_] "<x/>")
                    auth/authenticate    (fn [_] (throw (ex-info "boom" {:reason :bad-token})))
                    session/submit-invoice (fn [_] (reset! session-called true) {})
                    ksef/getenv          (stub-getenv {"KSEF_TEST" "ref|ctx|secret"})]
        (let [output (with-captured-out
                       #(is (nil? (ksef/submit-to-ksef
                                    (assoc base-invoice :ksef ksef-meta) pdf))))]
          (is (re-find #"KSeF FAILED: boom" output))))
      (is (false? @session-called) "session is not reached when auth throws")
      (let [stem (str/replace (.getPath pdf) #"\.pdf$" "")]
        (is (not (.exists (io/file (str stem ".ksef.xml")))))
        (is (not (.exists (io/file (str stem ".upo.xml")))))))))

(deftest submit-to-ksef-session-throws-is-caught
  (testing "session failure is logged, returns nil"
    (let [pdf (tmp-pdf "session-boom")]
      (with-redefs [xml/invoice->fa3-xml (fn [_] "<x/>")
                    auth/authenticate    (fn [_] {:access-token "A"})
                    session/submit-invoice (fn [_] (throw (ex-info "rejected" {})))
                    ksef/getenv          (stub-getenv {"KSEF_TEST" "tok"})]
        (let [output (with-captured-out
                       #(is (nil? (ksef/submit-to-ksef
                                    (assoc base-invoice :ksef ksef-meta) pdf))))]
          (is (re-find #"KSeF FAILED: rejected" output)))))))

(deftest submit-to-ksef-catch-prints-ex-data-detail
  (testing "failures carrying ex-info session context print a second
            indented line with session-ref / invoice-ref / status so
            oncall operators can tell a 440 from a 445 without a debugger"
    (let [pdf (tmp-pdf "rich-detail")]
      (with-redefs [xml/invoice->fa3-xml (fn [_] "<x/>")
                    auth/authenticate    (fn [_] {:access-token "A"})
                    session/submit-invoice
                    (fn [_]
                      (throw (ex-info "KSeF session failed and no fallback ksefNumber available"
                                      {:session-ref "SES-ABC"
                                       :invoice-ref "INV-123"
                                       :session-status {:code 445 :description "brak poprawnych faktur"}
                                       :invoice-status {:status {:code 440}}})))
                    ksef/getenv          (stub-getenv {"KSEF_TEST" "tok"})]
        (let [output (with-captured-out
                       #(is (nil? (ksef/submit-to-ksef
                                    (assoc base-invoice :ksef ksef-meta) pdf))))]
          (is (re-find #"KSeF FAILED: KSeF session failed" output))
          (is (re-find #"SES-ABC" output) "second line must surface session-ref")
          (is (re-find #"INV-123" output) "second line must surface invoice-ref")
          (is (re-find #"445" output) "second line must surface session status code"))))))

(deftest submit-to-ksef-catch-surfaces-auth-level-ex-data
  (testing "auth-level ex-data (top-level :status map from poll-auth-status)
            surfaces :code, :description, and :details on separate lines so a
            stale-token 450 reports its Polish description + 'Invalid token'
            detail — the previous select-keys filter silently dropped this
            because it only covered :session-status / :invoice-status"
    (let [pdf (tmp-pdf "auth-rich-detail")]
      (with-redefs [xml/invoice->fa3-xml (fn [_] (throw (AssertionError. "xml after auth")))
                    auth/authenticate
                    (fn [_]
                      (throw (ex-info "KSeF authentication failed"
                                      {:status {:code 450
                                                :description "Uwierzytelnianie zakończone niepowodzeniem z powodu błędnego tokenu"
                                                :details ["Invalid token"]}})))
                    session/submit-invoice (fn [_] (throw (AssertionError. "session after auth")))
                    ksef/getenv (stub-getenv {"KSEF_TEST" "stale-token"})]
        (let [output (with-captured-out
                       #(is (nil? (ksef/submit-to-ksef
                                    (assoc base-invoice :ksef ksef-meta) pdf))))]
          (is (re-find #"KSeF FAILED: KSeF authentication failed" output))
          (is (re-find #"450" output)
              "auth status code must appear in the detail output")
          (is (re-find #"Uwierzytelnianie" output)
              "Polish description from KSeF must appear verbatim so the user
               can correlate with the KSeF dashboard / email alerts")
          (is (re-find #"Invalid token" output)
              ":details array entries must surface so the specific failure
               reason is visible without rerunning under a debugger"))))))

(deftest submit-to-ksef-catch-nil-message-falls-back-to-class
  (testing "when the throwable's getMessage returns nil, fall back to the
            class simple name so the log still identifies the failure"
    (let [pdf (tmp-pdf "null-msg")]
      (with-redefs [xml/invoice->fa3-xml (fn [_] "<x/>")
                    auth/authenticate    (fn [_] (throw (NullPointerException.)))
                    session/submit-invoice (fn [_] (throw (AssertionError. "unreachable")))
                    ksef/getenv          (stub-getenv {"KSEF_TEST" "tok"})]
        (let [output (with-captured-out
                       #(is (nil? (ksef/submit-to-ksef
                                    (assoc base-invoice :ksef ksef-meta) pdf))))]
          (is (re-find #"KSeF FAILED: NullPointerException" output)
              "nil message must surface the class name instead of 'null'"))))))

(deftest sidecar-path-handles-non-trailing-pdf
  (testing "sidecar-path trims an anchored `.pdf` suffix; arbitrary `.pdf`
            substrings mid-path are NOT touched. Regex `\\.pdf$` on .getPath
            was already end-anchored, but an exact-char-count subs is more
            obviously correct."
    (let [sidecar-path @#'invoices.ksef/sidecar-path
          f (java.io.File. "/tmp/claude-10000/foo.pdf.bak/invoice.pdf")]
      (is (= "/tmp/claude-10000/foo.pdf.bak/invoice.ksef.xml"
             (sidecar-path f ".ksef.xml"))
          "trims only the final `.pdf` — mid-path .pdf is preserved"))
    (let [sidecar-path @#'invoices.ksef/sidecar-path
          f (java.io.File. "/tmp/claude-10000/invoice.pdf")]
      (is (= "/tmp/claude-10000/invoice.ksef.xml"
             (sidecar-path f ".ksef.xml")))
      (is (= "/tmp/claude-10000/invoice.upo.xml"
             (sidecar-path f ".upo.xml"))))
    (let [sidecar-path @#'invoices.ksef/sidecar-path
          f (java.io.File. "/tmp/claude-10000/no-extension")]
      (is (= "/tmp/claude-10000/no-extension.ksef.xml"
             (sidecar-path f ".ksef.xml"))
          "no .pdf suffix → just append"))))

(deftest resolve-ksef-config-test
  (let [seller-with-ksef {:nip 1234567890
                          :ksef {:env :test
                                 :token-env "KSEF_TOKEN"
                                 :schema :fa-3}}
        seller-no-ksef   {:nip 1234567890}]

    (testing "(a) seller-only inheritance: invoice has no :ksef key → uses seller's"
      (let [result (ksef/resolve-ksef-config seller-with-ksef {:buyer {} :items []})]
        (is (= :test (:env result)))
        (is (= "KSEF_TOKEN" (:token-env result)))
        (is (= :fa-3 (:schema result)))
        (is (= 1234567890 (:nip result)) "NIP defaults from seller :nip")))

    (testing "(b) invoice override merge: invoice :ksef merges over seller's"
      (let [result (ksef/resolve-ksef-config seller-with-ksef
                                              {:ksef {:env :prod}})]
        (is (= :prod (:env result)) "invoice key wins")
        (is (= "KSEF_TOKEN" (:token-env result)) "unchanged seller key inherited")
        (is (= :fa-3 (:schema result)) "unchanged seller key inherited")))

    (testing "(c) explicit opt-out with :ksef nil → nil even if seller has :ksef"
      (is (nil? (ksef/resolve-ksef-config seller-with-ksef {:ksef nil}))))

    (testing "(c') explicit opt-out with :ksef false → nil (same as nil)"
      (is (nil? (ksef/resolve-ksef-config seller-with-ksef {:ksef false}))))

    (testing "(d) legacy invoice-only: no seller :ksef, invoice :ksef → passes through"
      (let [result (ksef/resolve-ksef-config
                     seller-no-ksef
                     {:ksef {:env :test :token-env "T" :schema :fa-3}})]
        (is (= :test (:env result)))
        (is (= "T" (:token-env result)))
        (is (= 1234567890 (:nip result)) "NIP still defaults from seller :nip")))

    (testing "(e) NIP fallback from seller when seller :ksef has no :nip"
      (let [result (ksef/resolve-ksef-config seller-with-ksef {:buyer {} :items []})]
        (is (= 1234567890 (:nip result)))))

    (testing "(f) NIP override at invoice :ksef level (branch/subsidiary billing)"
      (let [result (ksef/resolve-ksef-config seller-with-ksef
                                              {:ksef {:nip 9999999999}})]
        (is (= 9999999999 (:nip result)) "invoice override wins over seller NIP")))

    (testing "empty invoice :ksef map inherits seller's full config (distinct from opt-out)"
      (let [result (ksef/resolve-ksef-config seller-with-ksef {:ksef {}})]
        (is (= :test (:env result)))
        (is (= "KSEF_TOKEN" (:token-env result)))
        (is (= :fa-3 (:schema result)))
        (is (= 1234567890 (:nip result)))))

    (testing "neither seller nor invoice has :ksef → nil"
      (is (nil? (ksef/resolve-ksef-config seller-no-ksef {:buyer {} :items []}))))

    (testing "explicit seller :ksef :nip is preserved (not overwritten by seller-level :nip)"
      (let [seller {:nip 1111111111
                    :ksef {:env :test :nip 2222222222
                           :token-env "K" :schema :fa-3}}
            result (ksef/resolve-ksef-config seller {})]
        (is (= 2222222222 (:nip result)) ":ksef :nip wins over top-level :nip")))))

(deftest env-url-lookup
  (testing ":test/:demo/:prod all resolve; unknown env fails fast inside the try"
    (let [pdf (tmp-pdf "env-check")
          seen-base (atom [])]
      (with-redefs [xml/invoice->fa3-xml (fn [_] "<x/>")
                    auth/authenticate    (fn [{:keys [base-url]}]
                                           (swap! seen-base conj base-url)
                                           {:access-token "A"})
                    session/submit-invoice (fn [_]
                                             {:ksef-number "1234567890-20260414-0100001AF629-AF"
                                              :upo-xml "<upo/>"
                                              :invoice-ref "I"
                                              :session-ref "S"})
                    ksef/getenv          (stub-getenv {"K" "tok"})]
        (doseq [env [:test :demo :prod]]
          (with-captured-out
            #(ksef/submit-to-ksef
               (assoc base-invoice :ksef {:env env :nip 1 :token-env "K" :schema :fa-3})
               pdf))))
      (is (= ["https://api-test.ksef.mf.gov.pl/v2"
              "https://api-demo.ksef.mf.gov.pl/v2"
              "https://api.ksef.mf.gov.pl/v2"]
             @seen-base))))

  (testing "bogus :env is caught by the outer try and reported as failure"
    (let [pdf (tmp-pdf "bad-env")]
      (with-redefs [xml/invoice->fa3-xml (fn [_] "<x/>")
                    auth/authenticate    (fn [_] (throw (AssertionError. "should not reach")))
                    session/submit-invoice (fn [_] (throw (AssertionError. "should not reach")))
                    ksef/getenv          (stub-getenv {"K" "tok"})]
        (let [output (with-captured-out
                       #(is (nil? (ksef/submit-to-ksef
                                    (assoc base-invoice :ksef
                                           {:env :staging :nip 1 :token-env "K" :schema :fa-3})
                                    pdf))))]
          (is (re-find #"KSeF FAILED: Unknown KSeF env" output)))))))
