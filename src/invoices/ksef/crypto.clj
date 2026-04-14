(ns invoices.ksef.crypto
  "Pure-function crypto primitives for KSeF (Polish e-invoicing) integration.

  All functions are stateless wrappers over the JDK's `javax.crypto` / `java.security`
  APIs — no external deps, no HTTP, no side effects beyond calls into the JCE.

  Two things are easy to get wrong and are called out here because KSeF rejects
  the request silently if either is off:

  * RSA-OAEP must use SHA-256 for BOTH the OAEP hash AND the MGF1 mask. The JDK's
    default cipher string `\"RSA/ECB/OAEPWithSHA-256AndMGF1Padding\"` pairs SHA-256
    with **MGF1-SHA1** unless you pass an explicit `OAEPParameterSpec`. We do.
  * AES-256-CBC with PKCS#7 — the JCE spells this `AES/CBC/PKCS5Padding`; PKCS5 and
    PKCS7 are identical for 16-byte block ciphers, so the name is a misnomer.
    KSeF sends the IV out-of-band, so we do NOT prepend it to the ciphertext."
  (:import [java.security KeyFactory MessageDigest SecureRandom]
           [java.security.cert CertificateFactory X509Certificate]
           [java.security.spec MGF1ParameterSpec]
           [java.util Base64]
           [javax.crypto Cipher KeyGenerator]
           [javax.crypto.spec IvParameterSpec OAEPParameterSpec PSource$PSpecified SecretKeySpec]
           [java.io ByteArrayInputStream]))

(def ^:private ^SecureRandom secure-random (SecureRandom.))

(defn base64-encode
  "Base64-encode bytes to an ASCII string (no line breaks)."
  ^String [^bytes data]
  (.encodeToString (Base64/getEncoder) data))

(defn base64-decode
  "Base64-decode a string to bytes."
  ^bytes [^String s]
  (.decode (Base64/getDecoder) s))

(defn sha-256
  "Return the 32-byte SHA-256 digest of `data`."
  ^bytes [^bytes data]
  (.digest (MessageDigest/getInstance "SHA-256") data))

(defn generate-aes-key
  "Generate a fresh 256-bit AES key. Returns a `javax.crypto.SecretKey`."
  []
  (let [kg (KeyGenerator/getInstance "AES")]
    (.init kg 256 secure-random)
    (.generateKey kg)))

(defn generate-iv
  "Generate a fresh 128-bit (16-byte) random IV as a byte array."
  ^bytes []
  (let [iv (byte-array 16)]
    (.nextBytes secure-random iv)
    iv))

(defn- ^OAEPParameterSpec oaep-sha256-params []
  (OAEPParameterSpec. "SHA-256" "MGF1" MGF1ParameterSpec/SHA256 PSource$PSpecified/DEFAULT))

(defn rsa-oaep-sha256-encrypt
  "RSA-OAEP encrypt `plaintext` under `public-key` with SHA-256 for both the OAEP
  hash and the MGF1 mask. Returns the ciphertext bytes."
  ^bytes [public-key ^bytes plaintext]
  (let [cipher (Cipher/getInstance "RSA/ECB/OAEPWithSHA-256AndMGF1Padding")]
    (.init cipher Cipher/ENCRYPT_MODE public-key (oaep-sha256-params))
    (.doFinal cipher plaintext)))

(defn rsa-oaep-sha256-decrypt
  "Inverse of `rsa-oaep-sha256-encrypt`. Exposed primarily for tests / round-trips."
  ^bytes [private-key ^bytes ciphertext]
  (let [cipher (Cipher/getInstance "RSA/ECB/OAEPWithSHA-256AndMGF1Padding")]
    (.init cipher Cipher/DECRYPT_MODE private-key (oaep-sha256-params))
    (.doFinal cipher ciphertext)))

(defn- ^SecretKeySpec ->aes-key-spec [key]
  (cond
    (instance? SecretKeySpec key) key
    (instance? javax.crypto.SecretKey key) (SecretKeySpec. (.getEncoded ^javax.crypto.SecretKey key) "AES")
    (bytes? key) (SecretKeySpec. ^bytes key "AES")
    :else (throw (IllegalArgumentException.
                   (str "Unsupported AES key type: " (class key))))))

(defn aes-256-cbc-encrypt
  "AES-256-CBC with PKCS#7 padding. `key` may be a `SecretKey` or a 32-byte array.
  `iv` must be a 16-byte array. Returns ciphertext bytes (IV is NOT prepended)."
  ^bytes [key ^bytes iv ^bytes plaintext]
  (let [cipher (Cipher/getInstance "AES/CBC/PKCS5Padding")]
    (.init cipher Cipher/ENCRYPT_MODE (->aes-key-spec key) (IvParameterSpec. iv))
    (.doFinal cipher plaintext)))

(defn aes-256-cbc-decrypt
  "Inverse of `aes-256-cbc-encrypt`. Exposed primarily for tests / round-trips."
  ^bytes [key ^bytes iv ^bytes ciphertext]
  (let [cipher (Cipher/getInstance "AES/CBC/PKCS5Padding")]
    (.init cipher Cipher/DECRYPT_MODE (->aes-key-spec key) (IvParameterSpec. iv))
    (.doFinal cipher ciphertext)))

(defn parse-x509-cert-der
  "Parse a DER-encoded X.509 certificate and return its `java.security.PublicKey`.
  Accepts either raw bytes or a base64-encoded string (as delivered by KSeF's
  `/security/public-key-certificates` endpoint)."
  [der]
  (let [^bytes bs (cond
                    (bytes? der) der
                    (string? der) (base64-decode der)
                    :else (throw (IllegalArgumentException.
                                   (str "Expected bytes or base64 string, got: " (class der)))))
        cf (CertificateFactory/getInstance "X.509")
        cert ^X509Certificate (.generateCertificate cf (ByteArrayInputStream. bs))]
    (.getPublicKey cert)))
