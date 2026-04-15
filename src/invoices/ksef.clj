(ns invoices.ksef
  "Façade over the invoices.ksef.* submodules: one entry point that the main
  `for-month` flow can call after `pdf/render` has produced the PDF file.

  `submit-to-ksef` must NEVER throw — any failure (missing env var, auth
  rejection, network error, malformed numer KSeF, timeout) is caught, logged
  to stdout, and reported as nil so that sibling invoices in the same run
  keep processing. KSeF is an additive sidecar, not a gate.

  Environment URL lookup is hard-coded here (not in submodules) so that
  auth/session stay environment-agnostic."
  (:require [clojure.string :as str]
            [invoices.ksef.auth :as auth]
            [invoices.ksef.session :as session]
            [invoices.ksef.xml :as xml]))

(def base-urls
  "Canonical KSeF base URL for each environment. No trailing slash."
  {:test "https://api-test.ksef.mf.gov.pl/v2"
   :demo "https://api-demo.ksef.mf.gov.pl/v2"
   :prod "https://api.ksef.mf.gov.pl/v2"})

(defn getenv
  "Thin wrapper over `System/getenv` so tests can rebind it via `with-redefs`
  (static Java methods are not rebindable)."
  [name]
  (System/getenv name))

(defn- sidecar-path [^java.io.File pdf-file ^String suffix]
  (str/replace (.getPath pdf-file) #"\.pdf$" suffix))

(defn- resolve-token
  "Pick the KSeF access token to use, given a `:ksef` config map.

  Returns `{:token <string>}` on success or `{:skip <reason>}` otherwise —
  the caller logs the reason and skips the submission.

  Precedence (env var beats literal when both are set and the env var is
  populated):

    1. `:token-env \"KSEF_TOKEN\"` set AND the named env var is non-blank
       → use the env var's value. The env var retains its runtime-override
       semantics: if an operator bothered to export it, honor their choice.
    2. `:token \"<literal>\"` set (literal token string in-config) → use it.
       Useful for single-machine setups where the config file already sits
       behind appropriate filesystem permissions and env-var indirection
       buys nothing. Security: the token is as readable as the config file.
    3. Neither → skip with an explanatory reason.

  When both are set but the env var is unset/blank, the literal wins
  (the env var was clearly intended as an optional override that wasn't
  exercised this run, not a required opt-in)."
  [{:keys [token token-env]}]
  (let [env-val (when token-env (getenv token-env))]
    (cond
      (not (str/blank? env-val))  {:token env-val}
      (not (str/blank? token))    {:token token}
      token-env                   {:skip (str "env var " token-env " not set")}
      :else                       {:skip ":token / :token-env missing from :ksef config"})))

(defn- write-sidecars!
  "Spit `<pdf>.ksef.xml` and — if present — `<pdf>.upo.xml` next to the PDF.
  `upo-xml` can be nil on the duplicate-detection path, where KSeF returns the
  original ksefNumber but no fresh UPO. Any I/O error propagates and is caught
  by the outer try in `submit-to-ksef`."
  [pdf-file invoice-xml upo-xml]
  (spit (sidecar-path pdf-file ".ksef.xml") invoice-xml)
  (when upo-xml
    (spit (sidecar-path pdf-file ".upo.xml") upo-xml)))

(defn resolve-ksef-config
  "Combine seller-level and invoice-level `:ksef` blocks into an effective
  config, or return nil to mean 'do not submit this invoice'.

  Merge semantics (pure — no I/O, no var access):

    * Invoice contains `:ksef` key with value nil or false → explicit opt-out.
      Return nil even if the seller has a :ksef block. This is the escape
      hatch for a run where most invoices go through KSeF but one shouldn't.
    * Invoice contains `:ksef` key with a map → `(merge seller-ksef invoice-ksef)`.
      Invoice keys override individual seller keys; an empty map inherits
      the seller's full config (distinct from nil-opt-out).
    * Invoice has no `:ksef` key → use the seller's block as-is.
    * Neither has `:ksef` → nil (current non-KSeF behavior).

  NIP fallback: if the effective config has no `:nip` but the seller has
  one at the top level, default to seller's :nip. Invoice-level :ksef :nip
  still wins (for branch/subsidiary billing).

  Legacy invoice-only configs (no seller :ksef, :ksef on the invoice) pass
  through unchanged — the merge with an empty base is a no-op."
  [seller invoice]
  (let [seller-ksef (:ksef seller)
        has-inv-key (contains? invoice :ksef)
        inv-ksef    (:ksef invoice)]
    (cond
      (and has-inv-key (not inv-ksef))
      nil

      has-inv-key
      (let [merged (merge seller-ksef inv-ksef)]
        (cond-> merged
          (and (not (:nip merged)) (:nip seller)) (assoc :nip (:nip seller))))

      seller-ksef
      (cond-> seller-ksef
        (and (not (:nip seller-ksef)) (:nip seller)) (assoc :nip (:nip seller)))

      :else nil)))

(defn- resolve-base-url [env]
  (or (get base-urls env)
      (throw (ex-info (str "Unknown KSeF env: " env
                           " — expected one of " (keys base-urls))
                      {:env env}))))

(defn submit-to-ksef
  "Submit a single already-rendered invoice to KSeF. Never throws.

  Expects the invoice map to already carry `:date` (LocalDate) and `:number`
  (the rendered invoice number string) in addition to the usual :seller,
  :buyer, :items, :ksef keys. Caller in `invoices.core/for-month` is
  responsible for merging those in before calling.

  Returns the full submission result map on success, or nil on skip/failure.
  Side effects: writes `<pdf>.ksef.xml` and `<pdf>.upo.xml` next to the PDF;
  prints a single status line to stdout."
  [invoice pdf-file]
  (let [{:keys [env nip schema] :or {schema :fa-3} :as ksef-config} (:ksef invoice)
        resolved (resolve-token ksef-config)]
    (if-let [reason (:skip resolved)]
      (do (println (str "    - KSeF submission skipped: " reason))
          nil)
      (try
        (let [base-url (resolve-base-url env)
              invoice-xml (xml/invoice->fa3-xml invoice)
              {:keys [access-token]} (auth/authenticate
                                       {:base-url base-url
                                        :nip nip
                                        :token (:token resolved)})
              result (session/submit-invoice
                       {:base-url base-url
                        :access-token access-token
                        :invoice-xml invoice-xml
                        :schema schema})]
          (write-sidecars! pdf-file invoice-xml (:upo-xml result))
          (println (str "    - KSeF accepted: " (:ksef-number result)))
          result)
        (catch Throwable t
          (println (str "    - KSeF FAILED: " (.getMessage t)))
          nil)))))
