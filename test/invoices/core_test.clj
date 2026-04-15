(ns invoices.core-test
  "Tests for the :ksef wiring in invoices.core/for-month and
  invoices.core/process-invoices. Everything IO-touching is mocked via
  `with-redefs`: pdf render, email send, callbacks, and the ksef façade."
  (:require [clojure.test :refer [deftest is testing]]
            [invoices.core :as core]
            [invoices.email :as email]
            [invoices.ksef :as ksef]
            [invoices.ksef.auth :as auth]
            [invoices.ksef.session :as session]
            [invoices.ksef.xml :as xml]
            [invoices.pdf :as pdf]))

(def ^:private fake-pdf (java.io.File. "/tmp/claude-10000/fake.pdf"))

(defn- invoice-fixture [extras]
  (merge {:seller {:name "S" :nip 1}
          :buyer  {:name "B" :email "b@example.com" :nip 2}
          :items  [{:vat 23 :netto 100 :title "t"}]}
         extras))

(deftest for-month-skips-ksef-without-key
  (testing "invoices without :ksef do NOT invoke the ksef façade"
    (let [ksef-calls (atom 0)]
      (with-redefs [pdf/render (fn [_ _ _] fake-pdf)
                    email/send-invoice (fn [_ _ _] nil)
                    ksef/submit-to-ksef (fn [_ _] (swap! ksef-calls inc))]
        (core/for-month (invoice-fixture {}) (java.time.LocalDate/parse "2026-04-14") 1))
      (is (zero? @ksef-calls)))))

(deftest for-month-invokes-ksef-when-configured
  (testing "invoices WITH :ksef invoke submit-to-ksef with the assembled map"
    (let [captured (atom nil)]
      (with-redefs [pdf/render (fn [_ _ _] fake-pdf)
                    email/send-invoice (fn [_ _ _] nil)
                    ksef/submit-to-ksef (fn [inv pdf] (reset! captured [inv pdf]) nil)]
        (core/for-month (invoice-fixture {:ksef {:env :test :nip 1
                                                 :token-env "K" :schema :fa-3}})
                        (java.time.LocalDate/parse "2026-04-14")
                        1))
      (is (some? @captured))
      (let [[inv pdf-arg] @captured]
        (is (= fake-pdf pdf-arg))
        (is (= (java.time.LocalDate/parse "2026-04-14") (:date inv))
            "date is threaded through for FA(3) XML generation")
        (is (= "1/4/2026" (:number inv))
            "invoice number is threaded through as the same string used on the PDF")))))

(deftest for-month-inherits-seller-level-ksef
  (testing "seller-level :ksef is inherited when the invoice has no :ksef key"
    (let [captured (atom nil)
          seller-ksef {:env :test :token-env "K" :schema :fa-3}]
      (with-redefs [pdf/render (fn [_ _ _] fake-pdf)
                    email/send-invoice (fn [_ _ _] nil)
                    ksef/submit-to-ksef (fn [inv _] (reset! captured inv) nil)]
        (core/for-month (invoice-fixture {:seller {:name "S" :nip 1 :ksef seller-ksef}})
                        (java.time.LocalDate/parse "2026-04-14")
                        1))
      (is (some? @captured) "submit-to-ksef invoked via seller inheritance")
      (let [{:keys [env token-env schema nip]} (:ksef @captured)]
        (is (= :test env))
        (is (= "K" token-env))
        (is (= :fa-3 schema))
        (is (= 1 nip) "NIP defaulted from seller top-level :nip")))))

(deftest for-month-invoice-ksef-nil-opts-out
  (testing ":ksef nil at invoice level opts out even if seller has :ksef"
    (let [called? (atom false)]
      (with-redefs [pdf/render (fn [_ _ _] fake-pdf)
                    email/send-invoice (fn [_ _ _] nil)
                    ksef/submit-to-ksef (fn [_ _] (reset! called? true) nil)]
        (core/for-month (invoice-fixture {:seller {:name "S" :nip 1
                                                    :ksef {:env :test :token-env "K"
                                                           :schema :fa-3}}
                                          :ksef nil})
                        (java.time.LocalDate/parse "2026-04-14")
                        1))
      (is (false? @called?) "submit-to-ksef not invoked when invoice opts out with :ksef nil"))))

(deftest process-invoices-survives-ksef-failure
  (testing "a throwing ksef chain on invoice N does not interrupt N+1"
    (let [pdf-renders (atom 0)
          xml-calls   (atom 0)
          worklogs {}
          invoices [(invoice-fixture {:ksef {:env :test :nip 1
                                             :token-env "K" :schema :fa-3}})
                    (invoice-fixture {:buyer {:name "B2" :email "b2@example.com" :nip 3}})
                    (invoice-fixture {:ksef {:env :test :nip 2
                                             :token-env "K" :schema :fa-3}})]
          config {:invoices invoices}]
      ;; Exercise the REAL façade so its outer try/catch is what swallows the
      ;; crash. Stubbing an inner fn to throw proves the never-throw contract:
      ;; sibling invoices must keep processing because the façade catches,
      ;; not because core/for-month has its own defensive catch.
      (with-redefs [pdf/render (fn [_ _ _] (swap! pdf-renders inc) fake-pdf)
                    email/send-invoice (fn [_ _ _] nil)
                    xml/invoice->fa3-xml (fn [_]
                                           (swap! xml-calls inc)
                                           (throw (RuntimeException. "simulated xml crash")))
                    auth/authenticate (fn [_] (throw (AssertionError. "should not reach auth")))
                    session/submit-invoice (fn [_] (throw (AssertionError. "should not reach session")))
                    ksef/getenv (fn [_] "tok")]
        (try
          (core/process-invoices config
                                 (java.time.LocalDate/parse "2026-04-14")
                                 worklogs)
          (catch Throwable t
            (is false (str "process-invoices should never throw, got: " (.getMessage t))))))
      (is (= 3 @pdf-renders) "all three PDFs rendered despite ksef crashes")
      (is (= 2 @xml-calls) "xml generator called only for the two invoices that had :ksef"))))
