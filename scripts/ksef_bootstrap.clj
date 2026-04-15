(ns ksef-bootstrap
  "Bootstrap a fresh KSEF_TEST_TOKEN by running the XAdES-BES authentication
  flow against api-test.ksef.mf.gov.pl. NOT production code — this is a dev
  script to mint test credentials when the existing KSEF_TEST_TOKEN is stale.

  The KSeF TEST env allows **self-signed** organization-seal certs; PROD will
  not. See /workspace/ksef-docs/auth/testowe-certyfikaty-i-podpisy-xades.md.

  ## Generating the seal cert (one-time, out-of-band)

  Needs an RSA-2048 self-signed cert whose Subject carries
  `organizationIdentifier=VATPL-{NIP}` (OID 2.5.4.97). openssl 3.x:

      NIP=6423166047
      openssl req -x509 -newkey rsa:2048 -nodes -days 7 \\
        -keyout /tmp/claude-10000/ksef-bootstrap-key.pem \\
        -out    /tmp/claude-10000/ksef-bootstrap-cert.pem \\
        -subj \"/C=PL/O=Mr Blobby Test Org/2.5.4.97=VATPL-$NIP/CN=Mr Blobby Test Seal\"

  ## Running

      LEIN_HOME=/home/claude/data/lein-home \\
      CLJ_CONFIG=/home/claude/data/clojure-home \\
      GITLIBS=/home/claude/data/gitlibs \\
      _JAVA_OPTIONS=\"-Djava.io.tmpdir=/tmp/claude-10000\" \\
      clojure -Sdeps '{:paths [\"src\" \"scripts\"]}' \\
        -M scripts/ksef_bootstrap.clj \\
          https://api-test.ksef.mf.gov.pl/v2 \\
          6423166047 \\
          /tmp/claude-10000/ksef-bootstrap-cert.pem \\
          /tmp/claude-10000/ksef-bootstrap-key.pem

  On success, writes a fresh
    `export KSEF_TEST_TOKEN='ref|nip-NIP|secret'`
  (plus KSEF_TEST_NIP / KSEF_TEST_BASE) to /tmp/claude-10000/ksef-env.sh.

  ## Gotcha worth remembering: serializer round-trip

  The JDK XMLDSig `.sign()` computes Reference digests over the in-memory DOM.
  If you then serialize with `javax.xml.transform.TransformerFactory`, it
  rewrites text nodes (e.g. it injects CR/LF into long base64 SignatureValue
  strings) — the document you send is NOT byte-for-byte equivalent to what
  the signer digested over, and KSeF responds with error 9105
  `\"Nieprawidłowa wartość skrótu dla referencji wskazującej na element ''\"`.
  We use `DOMImplementationLS.createLSSerializer` (DOM Level 3) for a stable
  round-trip. If you change the serializer, re-verify locally against a fresh
  KSeF call before touching anything else.

  ## Never commit cert material or the token to git."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream File StringReader]
           [java.nio.file Files Paths]
           [java.nio.file.attribute PosixFilePermissions]
           [java.security KeyFactory MessageDigest Security]
           [java.security.cert CertificateFactory X509Certificate]
           [java.security.spec PKCS8EncodedKeySpec]
           [java.util Base64 Collections UUID]
           [javax.xml.crypto URIDereferencer]
           [javax.xml.crypto.dom DOMStructure]
           [javax.xml.crypto.dsig XMLSignatureFactory Reference Transform CanonicalizationMethod SignatureMethod DigestMethod XMLObject]
           [javax.xml.crypto.dsig.dom DOMSignContext]
           [javax.xml.crypto.dsig.keyinfo KeyInfoFactory X509Data]
           [javax.xml.crypto.dsig.spec C14NMethodParameterSpec TransformParameterSpec]
           [javax.xml.parsers DocumentBuilderFactory]
           [javax.xml.transform OutputKeys TransformerFactory]
           [javax.xml.transform.dom DOMSource]
           [javax.xml.transform.stream StreamResult]
           [org.w3c.dom Document Node]))

(set! *warn-on-reflection* true)

;; ---------- PEM loading ----------

(defn- strip-pem ^String [^String pem ^String header]
  (-> pem
      (str/replace (str "-----BEGIN " header "-----") "")
      (str/replace (str "-----END " header "-----") "")
      (str/replace #"\s+" "")))

(defn- b64-decode ^bytes [^String s]
  (.decode (Base64/getDecoder) s))

(defn load-cert ^X509Certificate [pem-path]
  (let [pem (slurp pem-path)
        der (b64-decode (strip-pem pem "CERTIFICATE"))
        cf (CertificateFactory/getInstance "X.509")]
    (.generateCertificate cf (ByteArrayInputStream. der))))

(defn load-private-key
  "Load a PEM-encoded unencrypted PKCS#8 RSA private key. openssl's default
  output from `openssl req -nodes -keyout key.pem` is PKCS#8."
  [key-path]
  (let [pem (slurp key-path)
        header (cond
                 (str/includes? pem "BEGIN PRIVATE KEY") "PRIVATE KEY"
                 (str/includes? pem "BEGIN RSA PRIVATE KEY")
                 (throw (ex-info "Got PKCS#1 RSA key; re-generate with openssl pkcs8" {}))
                 :else (throw (ex-info "Unrecognized key format" {})))
        der (b64-decode (strip-pem pem header))
        spec (PKCS8EncodedKeySpec. der)
        kf (KeyFactory/getInstance "RSA")]
    (.generatePrivate kf spec)))

;; ---------- DOM helpers ----------

(defn- new-doc ^Document []
  (let [dbf (doto (DocumentBuilderFactory/newInstance) (.setNamespaceAware true))]
    (.newDocument (.newDocumentBuilder dbf))))

(defn- parse-doc ^Document [^String xml]
  (let [dbf (doto (DocumentBuilderFactory/newInstance) (.setNamespaceAware true))]
    (.parse (.newDocumentBuilder dbf)
            (ByteArrayInputStream. (.getBytes xml "UTF-8")))))

(defn- serialize ^String [^Node node]
  ;; Use the DOM Level 3 LSSerializer so the output is byte-stable after
  ;; a re-parse — TransformerFactory has a nasty habit of re-wrapping
  ;; text nodes (e.g. inserting CR/LF in base64 SignatureValue), which
  ;; breaks XML-DSig Reference digests on round-trip.
  (let [^org.w3c.dom.Document doc (if (instance? org.w3c.dom.Document node)
                                    node
                                    (.getOwnerDocument node))
        impl (.getImplementation doc)
        ls-impl (.getFeature impl "LS" "3.0")
        serializer (.createLSSerializer ls-impl)
        cfg (.getDomConfig serializer)]
    (when (.canSetParameter cfg "format-pretty-print" false)
      (.setParameter cfg "format-pretty-print" false))
    (when (.canSetParameter cfg "xml-declaration" false)
      (.setParameter cfg "xml-declaration" false))
    (.writeToString serializer node)))

(def ^:const ds-ns "http://www.w3.org/2000/09/xmldsig#")
(def ^:const xades-ns "http://uri.etsi.org/01903/v1.3.2#")
(def ^:const ksef-ns "http://ksef.mf.gov.pl/auth/token/2.0")

(defn- append-child ^Node [^Node parent ^Node child]
  (.appendChild parent child))

(defn- create-elem
  (^org.w3c.dom.Element [^Document doc ^String ns ^String qname]
   (.createElementNS doc ns qname))
  (^org.w3c.dom.Element [^Document doc ^String ns ^String qname ^String text]
   (let [e (.createElementNS doc ns qname)]
     (.appendChild e (.createTextNode doc text))
     e)))

;; ---------- AuthTokenRequest ----------

(defn build-auth-token-request
  "Build the AuthTokenRequest DOM (without signature). Per
  /workspace/ksef-docs/auth/context-identifier-nip.md:

    <AuthTokenRequest xmlns=\"http://ksef.mf.gov.pl/auth/token/2.0\">
      <Challenge>{challenge}</Challenge>
      <ContextIdentifier><Nip>{nip}</Nip></ContextIdentifier>
      <SubjectIdentifierType>certificateSubject</SubjectIdentifierType>
    </AuthTokenRequest>"
  ^Document [challenge nip]
  (let [doc (new-doc)
        root (create-elem doc ksef-ns "AuthTokenRequest")]
    (.appendChild doc root)
    (append-child root (create-elem doc ksef-ns "Challenge" challenge))
    (let [ci (create-elem doc ksef-ns "ContextIdentifier")]
      (append-child ci (create-elem doc ksef-ns "Nip" (str nip)))
      (append-child root ci))
    (append-child root (create-elem doc ksef-ns "SubjectIdentifierType" "certificateSubject"))
    doc))

;; ---------- XAdES SignedProperties ----------

(defn- sha256-b64 ^String [^bytes bs]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.encodeToString (Base64/getEncoder) (.digest md bs))))

(defn build-signed-properties
  "Build an xades:SignedProperties element as a DOM Element, bound to its own
  sub-document (so it can be wrapped in a DOMStructure and fed as an Object
  content for the XMLSignatureFactory).

  Structure per XAdES v1.3.2 and KSeF's /workspace/ksef-docs/auth/podpis-xades.md:

    <xades:SignedProperties Id=\"sigprop-id\">
      <xades:SignedSignatureProperties>
        <xades:SigningTime>ISO-instant</xades:SigningTime>
        <xades:SigningCertificate>
          <xades:Cert>
            <xades:CertDigest>
              <ds:DigestMethod Algorithm=\".../xmlenc#sha256\"/>
              <ds:DigestValue>base64-sha256-of-cert-der</ds:DigestValue>
            </xades:CertDigest>
            <xades:IssuerSerial>
              <ds:X509IssuerName>dn-string</ds:X509IssuerName>
              <ds:X509SerialNumber>decimal-serial</ds:X509SerialNumber>
            </xades:IssuerSerial>
          </xades:Cert>
        </xades:SigningCertificate>
      </xades:SignedSignatureProperties>
    </xades:SignedProperties>

  Returns {:element ... :id ...}. The Id is what the second Reference URI will
  point at (fragment #id)."
  [^X509Certificate cert]
  (let [doc (new-doc)
        sp-id (str "sigprop-" (subs (.toString (UUID/randomUUID)) 0 8))
        sp (create-elem doc xades-ns "xades:SignedProperties")
        _ (.setAttribute sp "Id" sp-id)
        _ (.setAttributeNS sp "http://www.w3.org/2000/xmlns/" "xmlns:xades" xades-ns)
        _ (.setAttributeNS sp "http://www.w3.org/2000/xmlns/" "xmlns:ds" ds-ns)
        _ (.appendChild doc sp)
        ssp (create-elem doc xades-ns "xades:SignedSignatureProperties")
        st (create-elem doc xades-ns "xades:SigningTime"
                        (str (java.time.Instant/now)))
        sc (create-elem doc xades-ns "xades:SigningCertificate")
        cert-el (create-elem doc xades-ns "xades:Cert")
        cd (create-elem doc xades-ns "xades:CertDigest")
        dm (create-elem doc ds-ns "ds:DigestMethod")
        _ (.setAttribute dm "Algorithm" "http://www.w3.org/2001/04/xmlenc#sha256")
        cert-der (.getEncoded cert)
        dv (create-elem doc ds-ns "ds:DigestValue" (sha256-b64 cert-der))
        is (create-elem doc xades-ns "xades:IssuerSerial")
        iss-name (create-elem doc ds-ns "ds:X509IssuerName"
                              (.getName (.getIssuerX500Principal cert)))
        iss-ser (create-elem doc ds-ns "ds:X509SerialNumber"
                             (.toString (.getSerialNumber cert)))]
    (.appendChild sp ssp)
    (.appendChild ssp st)
    (.appendChild ssp sc)
    (.appendChild sc cert-el)
    (.appendChild cert-el cd)
    (.appendChild cd dm)
    (.appendChild cd dv)
    (.appendChild cert-el is)
    (.appendChild is iss-name)
    (.appendChild is iss-ser)
    {:element sp :id sp-id :doc doc}))

;; ---------- XAdES-BES enveloped signature ----------

(defn sign-auth-token-request
  "Take the AuthTokenRequest document and sign it in place with XAdES-BES.
  After this call, the Document contains a ds:Signature child with the
  QualifyingProperties in a ds:Object, two References (enveloped doc + signed
  props), and the computed SignatureValue."
  [^Document atr-doc ^X509Certificate cert private-key]
  (let [^TransformParameterSpec nil-tps nil
        ^C14NMethodParameterSpec nil-cps nil
        fac (XMLSignatureFactory/getInstance "DOM")
        ;; Reference 1: the enveloped AuthTokenRequest itself (URI="")
        xform-env (.newTransform fac "http://www.w3.org/2000/09/xmldsig#enveloped-signature" nil-tps)
        xform-c14n (.newTransform fac "http://www.w3.org/TR/2001/REC-xml-c14n-20010315" nil-tps)
        digest-sha256 (.newDigestMethod fac "http://www.w3.org/2001/04/xmlenc#sha256" nil)
        ref1 (.newReference fac "" digest-sha256
                            (Collections/unmodifiableList
                              (doto (java.util.ArrayList.)
                                (.add xform-env)
                                (.add xform-c14n)))
                            nil nil)
        ;; Build XAdES SignedProperties
        {sp-el :element sp-id :id sp-doc :doc} (build-signed-properties cert)
        ;; Import the SignedProperties element into the ATR doc so we can wrap
        ;; it in a DOMStructure
        imported-sp (.importNode atr-doc sp-el true)
        ;; Reference 2: the SignedProperties (URI="#sp-id")
        ref2 (.newReference fac (str "#" sp-id)
                            digest-sha256
                            (Collections/singletonList
                              (.newTransform fac "http://www.w3.org/TR/2001/REC-xml-c14n-20010315" nil-tps))
                            "http://uri.etsi.org/01903#SignedProperties"
                            nil)
        ;; SignedInfo
        c14n (.newCanonicalizationMethod fac "http://www.w3.org/TR/2001/REC-xml-c14n-20010315" nil-cps)
        sig-method (.newSignatureMethod fac "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256" nil)
        si (.newSignedInfo fac c14n sig-method
                           (Collections/unmodifiableList
                             (doto (java.util.ArrayList.)
                               (.add ref1)
                               (.add ref2))))
        ;; KeyInfo with X509Certificate
        kif (KeyInfoFactory/getInstance)
        x509-data (.newX509Data kif (Collections/singletonList cert))
        ki (.newKeyInfo kif (Collections/singletonList x509-data))
        ;; QualifyingProperties wrapper around SignedProperties (the Object content)
        qp-doc (new-doc)
        qp (create-elem qp-doc xades-ns "xades:QualifyingProperties")
        _ (.setAttributeNS qp "http://www.w3.org/2000/xmlns/" "xmlns:xades" xades-ns)
        _ (.setAttribute qp "Target" "#sig")
        qp-imported-sp (.importNode qp-doc sp-el true)
        _ (.appendChild qp qp-imported-sp)
        _ (.appendChild qp-doc qp)
        ;; Import QualifyingProperties into the ATR doc
        qp-in-atr ^org.w3c.dom.Element (.importNode atr-doc qp true)
        ;; Mark Id attribute on the SignedProperties child so DOM's getElementById
        ;; (and by extension, the URI fragment resolver) can find it by "#sp-id".
        inner-sp (-> qp-in-atr
                     (.getElementsByTagNameNS xades-ns "SignedProperties")
                     (.item 0))
        _ (when inner-sp
            (.setIdAttributeNS ^org.w3c.dom.Element inner-sp nil "Id" true))
        obj-content (doto (java.util.ArrayList.)
                      (.add (DOMStructure. qp-in-atr)))
        xml-obj (.newXMLObject fac obj-content nil nil nil)
        signature (.newXMLSignature fac si ki
                                    (Collections/singletonList xml-obj)
                                    "sig" nil)
        sign-ctx (DOMSignContext. ^java.security.PrivateKey private-key
                                  ^org.w3c.dom.Node (.getDocumentElement atr-doc))]
    (.sign signature sign-ctx)
    atr-doc))

;; ---------- HTTP ----------

(defn- http-get-json [url & [opts]]
  (http/get url (merge {:as :json-strict :accept :json :throw-exceptions false}
                       opts)))

(defn- http-post-json [url body & [opts]]
  (http/post url (merge {:content-type :json :accept :json :as :json-strict
                         :throw-exceptions false
                         :body (json/generate-string body)}
                        opts)))

(defn- http-post-xml [url ^String xml & [opts]]
  (http/post url (merge {:content-type "application/xml"
                         :accept :json :as :json-strict
                         :throw-exceptions false
                         :body xml}
                        opts)))

;; ---------- Flow ----------

(defn fetch-challenge [base-url]
  (let [resp (http-post-json (str base-url "/auth/challenge") {})]
    (when-not (= 200 (:status resp))
      (throw (ex-info "challenge failed" resp)))
    (get-in resp [:body])))

(defn post-xades [base-url ^String signed-xml]
  (let [resp (http-post-xml (str base-url "/auth/xades-signature") signed-xml)]
    (println "  /auth/xades-signature →" (:status resp))
    (when-not (#{200 201 202} (:status resp))
      (throw (ex-info "xades-signature failed" resp)))
    (:body resp)))

(defn poll-auth [base-url ref auth-tok]
  (loop [i 0]
    (when (>= i 30) (throw (ex-info "auth poll timeout" {:ref ref})))
    (Thread/sleep 1500)
    (let [resp (http-get-json (str base-url "/auth/" ref)
                              {:headers {"Authorization" (str "Bearer " auth-tok)}})
          code (get-in resp [:body :status :code])]
      (println "  poll" i "status.code=" code
               (when (not= code 200) (pr-str (get-in resp [:body :status]))))
      (cond
        (= 200 code) (:body resp)
        (and code (not= 100 code)) (throw (ex-info "auth failed"
                                                   {:status (get-in resp [:body :status])}))
        :else (recur (inc i))))))

(defn redeem-tokens [base-url auth-tok]
  (let [resp (http-post-json (str base-url "/auth/token/redeem") {}
                             {:headers {"Authorization" (str "Bearer " auth-tok)}})]
    (when-not (#{200 201} (:status resp))
      (throw (ex-info "redeem failed" resp)))
    (get-in resp [:body])))

(defn create-ksef-token [base-url access-tok description permissions]
  (let [resp (http-post-json (str base-url "/tokens")
                             {:permissions permissions :description description}
                             {:headers {"Authorization" (str "Bearer " access-tok)}})]
    (when-not (#{200 201 202} (:status resp))
      (throw (ex-info "create ksef token failed" resp)))
    (:body resp)))

;; ---------- Env file merge ----------

(def ^:private test-token-line-pattern
  #"export KSEF_TEST_(TOKEN|NIP|BASE)=.*")

(defn- format-export [^String k v]
  (str "export KSEF_TEST_" k "='" v "'"))

(defn merge-test-token-lines
  "Merge fresh KSEF_TEST_{TOKEN,NIP,BASE} values into an existing env-file
  string, preserving every other line verbatim. Pure — no I/O.

  `existing` is the current file contents (or nil / blank for a new file).
  `values` is `{:token ..., :nip ..., :base-url ...}`.

  Lines matching `export KSEF_TEST_(TOKEN|NIP|BASE)=...` are replaced in
  place. Any of the three keys not present in `existing` are appended at
  the end under a short dated header. Everything else — comments, other
  exports (KSEF_DEMO_*, _JAVA_OPTIONS, CLJ_CONFIG, ...) — is preserved.

  CRLF input is normalized to LF on output. When `existing` is nil/blank,
  emits a fresh minimal file with a short header comment and the three
  exports."
  [^String existing {:keys [token nip base-url]}]
  (let [targets {"TOKEN" (format-export "TOKEN" token)
                 "NIP"   (format-export "NIP" nip)
                 "BASE"  (format-export "BASE" base-url)}
        today   (str (java.time.LocalDate/now))]
    (if (str/blank? existing)
      (str/join "\n"
                ["# KSeF test credentials — minted via scripts/ksef_bootstrap.clj"
                 (str "# Last update: " today)
                 "# Never commit this file."
                 (get targets "TOKEN")
                 (get targets "NIP")
                 (get targets "BASE")
                 ""])
      (let [lines    (str/split existing #"\r?\n" -1)
            seen     (atom #{})
            replaced (mapv (fn [line]
                             (if-let [[_ k] (re-matches test-token-line-pattern line)]
                               (do (swap! seen conj k) (get targets k))
                               line))
                           lines)
            missing  (remove @seen ["TOKEN" "NIP" "BASE"])]
        (if (empty? missing)
          (str/join "\n" replaced)
          ;; Drop a single trailing "" (from a final newline in `existing`) so
          ;; the appended header sits one blank line below the prior content
          ;; instead of two.
          (let [trimmed (cond-> replaced
                          (and (seq replaced) (= "" (peek replaced))) pop)]
            (str/join "\n"
                      (concat trimmed
                              [""
                               (str "# KSeF test credentials appended via scripts/ksef_bootstrap.clj on " today)]
                              (map #(get targets %) missing)))))))))

(defn- write-env-file!
  "Write `content` to `path` and chmod 600. Best-effort on non-POSIX
  filesystems (swallows UnsupportedOperationException so Windows dev
  still works)."
  [^String path ^String content]
  (spit path content)
  (try
    (Files/setPosixFilePermissions
      (Paths/get path (into-array String []))
      (PosixFilePermissions/fromString "rw-------"))
    (catch UnsupportedOperationException _ nil)))

(defn bootstrap [{:keys [base-url nip cert-pem-path key-pem-path out-env-file]}]
  (println "== Bootstrap KSeF test token via XAdES-BES ==")
  (println " base-url:" base-url)
  (println " nip:" nip)
  (println " cert:" cert-pem-path)
  (println)
  (let [cert (load-cert cert-pem-path)
        pk (load-private-key key-pem-path)
        _ (println "== (1) fetch challenge ==")
        {:keys [challenge timestampMs timestamp]} (fetch-challenge base-url)
        _ (println "  challenge=" challenge " timestampMs=" timestampMs)
        _ (println "== (2) build AuthTokenRequest ==")
        atr (build-auth-token-request challenge nip)
        _ (println "  unsigned:")
        _ (println (serialize atr))
        _ (println "== (3) sign XAdES-BES ==")
        _ (sign-auth-token-request atr cert pk)
        signed-xml (serialize atr)
        _ (println "  signed length=" (count signed-xml))
        _ (spit "/tmp/claude-10000/signed-atr.xml" signed-xml)
        _ (println "  wrote /tmp/claude-10000/signed-atr.xml")
        _ (println "== (4) POST /auth/xades-signature ==")
        submit-body (post-xades base-url signed-xml)
        ref-num (:referenceNumber submit-body)
        auth-tok (get-in submit-body [:authenticationToken :token])
        _ (println "  ref=" ref-num)
        _ (println "== (5) poll /auth/{ref} ==")
        _ (poll-auth base-url ref-num auth-tok)
        _ (println "== (6) redeem accessToken ==")
        tok-pair (redeem-tokens base-url auth-tok)
        access-tok (get-in tok-pair [:accessToken :token])
        _ (println "  access valid until=" (get-in tok-pair [:accessToken :validUntil]))
        _ (println "== (7) POST /tokens → new ksefToken ==")
        ksef-tok-resp (create-ksef-token base-url access-tok
                                          "Taborlin bootstrap test token"
                                          ["InvoiceRead" "InvoiceWrite"])
        _ (println "  ksef-token response:" (pr-str ksef-tok-resp))
        tok-str (:token ksef-tok-resp)]
    (when (and tok-str out-env-file)
      (let [f (File. ^String out-env-file)
            existing (when (.exists f) (slurp f))
            merged (merge-test-token-lines
                     existing
                     {:token tok-str :nip nip :base-url base-url})]
        (write-env-file! out-env-file merged)
        (println "  wrote" out-env-file
                 (if existing "(merged)" "(new)"))))
    (println "== DONE ==")
    ksef-tok-resp))

(defn -main [& args]
  (let [[base nip cert-p key-p] args]
    (bootstrap {:base-url base
                :nip nip
                :cert-pem-path cert-p
                :key-pem-path key-p
                :out-env-file "/tmp/claude-10000/ksef-env.sh"})))

;; Run via `clojure -M scripts/ksef_bootstrap.clj BASE NIP CERT KEY`.
(when (seq *command-line-args*)
  (apply -main *command-line-args*))
