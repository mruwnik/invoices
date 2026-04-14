(ns invoices.ksef.crypto-test
  (:require [clojure.test :refer [deftest is are testing]]
            [invoices.ksef.crypto :as crypto])
  (:import [java.security KeyPairGenerator]
           [java.security.interfaces RSAPublicKey]
           [java.security.spec MGF1ParameterSpec]
           [javax.crypto Cipher]
           [javax.crypto.spec OAEPParameterSpec PSource$PSpecified]))

(defn- rsa-keypair []
  (let [kpg (KeyPairGenerator/getInstance "RSA")]
    (.initialize kpg 2048)
    (.generateKeyPair kpg)))

(defn- utf8 ^bytes [^String s] (.getBytes s "UTF-8"))
(defn- ->str ^String [^bytes bs] (String. bs "UTF-8"))

(deftest rsa-oaep-round-trip
  (let [kp (rsa-keypair)
        pub (.getPublic kp)
        priv (.getPrivate kp)]
    (testing "round-trip on representative KSeF payload shapes"
      (are [plaintext]
           (= plaintext
              (->str (crypto/rsa-oaep-sha256-decrypt
                       priv (crypto/rsa-oaep-sha256-encrypt pub (utf8 plaintext)))))
        "token-123|1712345678901"
        ""
        "a"
        "zażółć gęślą jaźń"))
    (testing "each encryption is non-deterministic (OAEP randomization)"
      (let [pt (utf8 "same-input")
            c1 (crypto/rsa-oaep-sha256-encrypt pub pt)
            c2 (crypto/rsa-oaep-sha256-encrypt pub pt)]
        (is (not= (seq c1) (seq c2)))
        (is (= (seq pt) (seq (crypto/rsa-oaep-sha256-decrypt priv c1))))
        (is (= (seq pt) (seq (crypto/rsa-oaep-sha256-decrypt priv c2))))))))

(defn- decrypt-with-mgf1 [priv ^bytes ciphertext mgf1-spec]
  (let [cipher (Cipher/getInstance "RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        spec (OAEPParameterSpec. "SHA-256" "MGF1" mgf1-spec PSource$PSpecified/DEFAULT)]
    (.init cipher Cipher/DECRYPT_MODE priv spec)
    (.doFinal cipher ciphertext)))

(deftest rsa-oaep-uses-mgf1-sha256-not-sha1
  (testing "KSeF-rejects-silently regression guard: ciphertext MUST decrypt
    under MGF1-SHA256 and MUST NOT decrypt under MGF1-SHA1. If this fails,
    someone dropped the explicit OAEPParameterSpec and the JDK fell back
    to its default MGF1-SHA1 pairing."
    (let [kp (rsa-keypair)
          pub (.getPublic kp)
          priv (.getPrivate kp)
          ct (crypto/rsa-oaep-sha256-encrypt pub (utf8 "ksef-token|1712345678901"))]
      (is (= "ksef-token|1712345678901"
             (->str (decrypt-with-mgf1 priv ct MGF1ParameterSpec/SHA256))))
      (is (thrown? Exception
                   (decrypt-with-mgf1 priv ct MGF1ParameterSpec/SHA1))))))

(deftest aes-256-cbc-round-trip
  (let [key (crypto/generate-aes-key)
        iv (crypto/generate-iv)]
    (is (= 16 (count iv)))
    (is (= 32 (count (.getEncoded key))))
    (testing "round-trip across payload sizes (exercises PKCS7 padding)"
      (are [plaintext]
           (= plaintext
              (->str (crypto/aes-256-cbc-decrypt
                       key iv (crypto/aes-256-cbc-encrypt key iv (utf8 plaintext)))))
        ""
        "a"
        "exactly-16-bytes"            ;; 16 bytes → full padding block
        "a bit over one block of data"
        (apply str (repeat 1024 "x"))))
    (testing "ciphertext is not the plaintext"
      (let [pt (utf8 "hello world")
            ct (crypto/aes-256-cbc-encrypt key iv pt)]
        (is (not= (seq pt) (seq ct)))
        (is (zero? (mod (count ct) 16)))))
    (testing "accepts raw byte[] key as well as SecretKey"
      (let [raw (.getEncoded key)
            pt (utf8 "key-form-check")
            ct (crypto/aes-256-cbc-encrypt raw iv pt)]
        (is (= (seq pt) (seq (crypto/aes-256-cbc-decrypt key iv ct))))))))

(defn- hex ^String [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(deftest sha-256-known-vectors
  (are [input expected-hex]
       (= expected-hex (hex (crypto/sha-256 (utf8 input))))
    ""
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    "abc"
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    "The quick brown fox jumps over the lazy dog"
    "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592"))

(defn- b64-round-trip-ok? [input]
  (let [src (byte-array input)]
    (= (seq src)
       (seq (crypto/base64-decode (crypto/base64-encode src))))))

(deftest base64-round-trip
  (are [input] (b64-round-trip-ok? input)
    []
    [0]
    [0 1 2]
    [-1 -2 -3 -4 -5]
    (range 0 32)
    (range -128 128))
  (testing "known vector"
    (is (= "aGVsbG8=" (crypto/base64-encode (utf8 "hello"))))
    (is (= "hello" (->str (crypto/base64-decode "aGVsbG8="))))))

;; Self-signed X.509 fixture generated with:
;;   openssl req -x509 -newkey rsa:2048 -keyout /dev/null -out fixture.pem \
;;     -days 3650 -nodes -subj "/CN=ksef-test-fixture"
;;   openssl x509 -in fixture.pem -outform DER -out fixture.der
;;   base64 -w0 fixture.der
;; It carries an RSA-2048 public key and is used only to exercise the parser.
(def ^:private fixture-cert-b64
  (str "MIIDGTCCAgGgAwIBAgIUd28sfZTqd0/MkB/5vdzKCWetaDIwDQYJKoZIhvcNAQEL"
       "BQAwHDEaMBgGA1UEAwwRa3NlZi10ZXN0LWZpeHR1cmUwHhcNMjYwNDE0MTkwNDM2"
       "WhcNMzYwNDExMTkwNDM2WjAcMRowGAYDVQQDDBFrc2VmLXRlc3QtZml4dHVyZTCC"
       "ASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAM4jUNd5bbqHgQT8tq+i0N20"
       "DL8VOqKKk9tHU5GsfYaGc3+kTt8oGqO0hJcMAnyQh1iQyVGm5CViszD9CaQEKnRs"
       "8M8nxoI1AiCg/R/IfUcAkW2woYBaWfU9fMA/AzdUGgCjw3bv9SI5JDT49+SecsW5"
       "+SGH9wYSfMkOm7DGwSHhBrnS0/rXjWViBGaj4LrqzNBjvsypi5lnY8pWFXgm95p5"
       "BGJsz0QH6vHHeKWGZ06GhzH8WRVtypsHqwm23l2z6QPcdQoTMisR75bdFPrifUGk"
       "QMIeroj3uSWbZPwTTTDBIxYjeXZ+fBsBfd1DNjd3dqNflbioBQqUV3WD4wZAiv8C"
       "AwEAAaNTMFEwHQYDVR0OBBYEFA/6VexsB9K7FrTxlg0TyPNr5FstMB8GA1UdIwQY"
       "MBaAFA/6VexsB9K7FrTxlg0TyPNr5FstMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZI"
       "hvcNAQELBQADggEBACbuR0zResqToVnlEk7EeLkdY4JxN56yzwdKxdDDq64JkNfH"
       "I9CvRXWDvvaqZDjXYH56fGJf8IYFouMMDkghVYRytzXDA7BjvCigP23BNBO2IgsY"
       "NZ7BXqSLTJmyB03KGRJzBTpvR9r7Ex45d38xTMgmWO6E8dYnzatEbr4b8xGE1cl8"
       "0pMD64iXgmQGQbzka7kZXau33KRxkaR6uaOvx7A3c+FnJL/vdErDf3xdxfgO6FIb"
       "iRuDlDHxxH+YCZNCYXz9on2lTXxGCfVK4xatNfyE/Et7aVV9kOeVm7MTxYJ7R1lo"
       "c3i6W9Lh8ccGEf6UpU5EkyjtPRD9MGhV1GKAyno="))

(deftest parse-x509-cert-der-extracts-rsa-public-key
  (testing "accepts base64 string"
    (let [pk (crypto/parse-x509-cert-der fixture-cert-b64)]
      (is (instance? RSAPublicKey pk))
      (is (= "RSA" (.getAlgorithm pk)))
      (is (= 2048 (.bitLength (.getModulus ^RSAPublicKey pk))))))
  (testing "accepts raw DER bytes"
    (let [bs (crypto/base64-decode fixture-cert-b64)
          pk (crypto/parse-x509-cert-der bs)]
      (is (instance? RSAPublicKey pk))))
  (testing "the parsed key actually works for RSA-OAEP encryption"
    (let [pk (crypto/parse-x509-cert-der fixture-cert-b64)
          ct (crypto/rsa-oaep-sha256-encrypt pk (utf8 "sanity"))]
      (is (pos? (count ct))))))
