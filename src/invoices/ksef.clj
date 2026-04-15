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

(defn- write-sidecars!
  "Spit `<pdf>.ksef.xml` and `<pdf>.upo.xml` next to the PDF. Any I/O error
  propagates and is caught by the outer try in `submit-to-ksef`."
  [pdf-file invoice-xml upo-xml]
  (spit (sidecar-path pdf-file ".ksef.xml") invoice-xml)
  (spit (sidecar-path pdf-file ".upo.xml") upo-xml))

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
  (let [{:keys [env nip token-env schema] :or {schema :fa-3}} (:ksef invoice)
        token (when token-env (getenv token-env))]
    (cond
      (nil? token-env)
      (do (println "    - KSeF submission skipped: :token-env missing from :ksef config")
          nil)

      (str/blank? token)
      (do (println (str "    - KSeF submission skipped: env var " token-env " not set"))
          nil)

      :else
      (try
        (let [base-url (resolve-base-url env)
              invoice-xml (xml/invoice->fa3-xml invoice)
              {:keys [access-token]} (auth/authenticate
                                       {:base-url base-url
                                        :nip nip
                                        :token token})
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
