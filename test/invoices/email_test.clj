(ns invoices.email-test
  (:require [invoices.email :as email :refer [split-cells zip-item parse-double]]
            [clojure.test :refer :all])
  (:import [jakarta.mail Session Message$RecipientType Multipart]
           [jakarta.mail.internet MimeMessage MimeBodyPart]
           [java.util Properties]))


(deftest test-split-cells
  (let [cells [["row1-col1", "row1-col2", "row1-col3"]
               ["row2-col1", "row2-col2", "row2-col3"]
               ["row3-col1", "row3-col2", "row3-col3"]]]
    (testing "Check whether cells get split correctly"
      (is (= cells (split-cells "row1-col1;row1-col2;row1-col3\nrow2-col1;row2-col2;row2-col3\nrow3-col1;row3-col2;row3-col3\n")))
      (is (= cells (split-cells "row1-col1 row1-col2 row1-col3\nrow2-col1 row2-col2 row2-col3\nrow3-col1 row3-col2 row3-col3\n")))
      (is (= cells (split-cells "row1-col1	row1-col2	row1-col3\nrow2-col1	row2-col2	row2-col3\nrow3-col1	row3-col2	row3-col3\n")))
      (is (= cells (split-cells "row1-col1;  row1-col2;  row1-col3\nrow2-col1;  row2-col2;  row2-col3\nrow3-col1;  row3-col2;  row3-col3\n"))))
    (testing "Check whether extra whitespace gets removed"
      (is (= cells (split-cells "row1-col1     row1-col2     row1-col3\nrow2-col1      row2-col2     row2-col3\nrow3-col1; \trow3-col2 row3-col3\n"))))))

(deftest test-zip-item
  (testing "Check whether items get correctly zipped"
    (is (= (zip-item [:coll1 :coll2 :coll3] ["row1-col1", "row1-col2", "row1-col3"])
           {:coll1 "row1-col1" :coll2 "row1-col2" :coll3 "row1-col3"}))))

(deftest test-parse-double
  (testing "Check whether doubles get correctly parsed"
    (is (= (parse-double "123") 123.0))
    (is (= (parse-double "123.1234") 123.1234))
    (is (= (parse-double "123.654321543") 123.654321543))
    (is (= (parse-double "0.00000") 0.0))
    (is (= (parse-double "-123") -123.0))
    (is (= (parse-double "asdasd123adasd32") 123.0))))

(defn- make-tmp-pdf []
  (let [f (java.io.File/createTempFile "invoices-test-" ".pdf")]
    (.deleteOnExit f)
    (spit f "fake pdf content")
    f))

(defn- first-address-str [^MimeMessage msg type]
  (-> msg (.getRecipients type) first .toString))

(deftest test-smtp-properties
  (testing "STARTTLS (:tls) defaults to port 587 and enables starttls.enable"
    (let [p (email/smtp-properties {:host "smtp.example.com" :tls true})]
      (is (= "smtp.example.com" (.get p "mail.smtp.host")))
      (is (= "587" (.get p "mail.smtp.port")))
      (is (= "true" (.get p "mail.smtp.starttls.enable")))
      (is (= "true" (.get p "mail.smtp.auth")))
      (is (nil? (.get p "mail.smtp.ssl.enable")))))
  (testing "SMTPS (:ssl) defaults to port 465 and enables ssl.enable"
    (let [p (email/smtp-properties {:host "smtp.example.com" :ssl true})]
      (is (= "465" (.get p "mail.smtp.port")))
      (is (= "true" (.get p "mail.smtp.ssl.enable")))
      (is (nil? (.get p "mail.smtp.starttls.enable")))))
  (testing "Plain SMTP defaults to port 25"
    (let [p (email/smtp-properties {:host "smtp.example.com"})]
      (is (= "25" (.get p "mail.smtp.port")))))
  (testing "Explicit port wins"
    (let [p (email/smtp-properties {:host "h" :port 2525 :tls true})]
      (is (= "2525" (.get p "mail.smtp.port"))))))

(deftest test-build-message
  (testing "build-message constructs a MimeMessage with From/To/Subject/attachment"
    (let [pdf (make-tmp-pdf)
          session (Session/getInstance (Properties.) nil)
          msg (email/build-message session "sender@example.com" "recipient@example.com" "Test Subject" pdf)]
      (is (= "Test Subject" (.getSubject msg)))
      (is (= "sender@example.com" (-> msg .getFrom first .toString)))
      (is (= "recipient@example.com" (first-address-str msg Message$RecipientType/TO)))
      (let [content (.getContent msg)]
        (is (instance? Multipart content))
        (is (= 2 (.getCount ^Multipart content)))
        (let [attach ^MimeBodyPart (.getBodyPart ^Multipart content 1)]
          (is (= (.getName pdf) (.getFileName attach)))
          (is (some? (.getDataHandler attach))))))))

(deftest test-send-invoice-builds-and-sends
  (testing "send-invoice builds a MimeMessage and routes it to send-message! with SMTP creds"
    (let [pdf (make-tmp-pdf)
          captured (atom nil)]
      (with-redefs [email/send-message!
                    (fn [^MimeMessage msg user pass]
                      (reset! captured
                              {:user user
                               :pass pass
                               :subject (.getSubject msg)
                               :from (-> msg .getFrom first .toString)
                               :to (first-address-str msg Message$RecipientType/TO)
                               :content-class (class (.getContent msg))
                               :part-count (.getCount ^Multipart (.getContent msg))
                               :attachment-name (.getFileName ^MimeBodyPart
                                                              (.getBodyPart ^Multipart (.getContent msg) 1))}))]
        (email/send-invoice pdf "to@example.com"
                            {:host "smtp.example.com" :port 587
                             :user "me@example.com" :pass "secret" :tls true}))
      (let [c @captured]
        (is (= "me@example.com" (:from c)))
        (is (= "to@example.com" (:to c)))
        (is (= "me@example.com" (:user c)))
        (is (= "secret" (:pass c)))
        (is (= (.getName pdf) (:subject c)))
        (is (= (.getName pdf) (:attachment-name c)))
        (is (= 2 (:part-count c)))))))

(deftest test-send-invoice-handles-multiple-recipients
  (testing "sequential :to recurses and sends one message per address"
    (let [pdf (make-tmp-pdf)
          addrs (atom [])]
      (with-redefs [email/send-message!
                    (fn [^MimeMessage msg _ _]
                      (swap! addrs conj (first-address-str msg Message$RecipientType/TO)))]
        (email/send-invoice pdf ["a@x.com" "b@y.com" "c@z.com"]
                            {:host "h" :user "u@x" :pass "p" :tls true}))
      (is (= ["a@x.com" "b@y.com" "c@z.com"] @addrs)))))

(deftest test-send-invoice-swallows-exceptions
  (testing "a Transport failure does not propagate out of send-invoice"
    (let [pdf (make-tmp-pdf)]
      (with-redefs [email/send-message!
                    (fn [_ _ _] (throw (RuntimeException. "boom")))]
        (is (nil? (email/send-invoice pdf "x@y" {:host "h" :user "u@x" :pass "p" :tls true})))))))

(deftest test-send-invoice-ignores-nil-inputs
  (testing "nil smtp / nil recipient are no-ops (no throw, no send)"
    (let [pdf (make-tmp-pdf)
          called (atom 0)]
      (with-redefs [email/send-message! (fn [_ _ _] (swap! called inc))]
        (email/send-invoice pdf nil {:host "h" :user "u@x" :pass "p"})
        (email/send-invoice pdf "to@x" nil)
        (email/send-invoice pdf "to@x" {:host "h" :pass "p"})) ;; no :user
      (is (= 0 @called)))))
