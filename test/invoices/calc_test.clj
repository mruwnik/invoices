(ns invoices.calc-test
  (:require [clojure.test :refer :all]
            [invoices.calc :refer :all]))


(deftest test-round
  (testing "Rounding to 2 decimal points"
    (is (== (round 10) 10.0))
    (is (== (round 10.1) 10.1))
    (is (== (round 10.12) 10.12))
    (is (== (round 10.123) 10.12))
    (is (== (round 10.1234) 10.12)))

  (testing "Rounding is correct"
    (is (== (round 10.5) 10.5))
    (is (== (round 10.119) 10.12))
    (is (== (round 10.155) 10.15))
    (is (== (round 10.1251) 10.13))))


(deftest test-vat
  (testing "Check vat calculations"
    (is (= (vat {:netto 1000 :vat 23}) 230))
    (is (= (vat {:netto 1000 :vat 8}) 80))
    (is (= (vat {:netto 1000 :vat 0}) 0))
    (is (= (vat {:netto 0 :vat 23}) 0))
    (is (= (vat {:netto 1000 :vat -8}) -80))))

(deftest test-brutto
  (testing "Check whether calculating brutto works"
    (is (= (brutto {:netto 1000 :vat 23}) 1230.0))
    (is (= (brutto {:netto 1000 :vat 8}) 1080.0))
    (is (= (brutto {:netto 1000 :vat 0}) 1000.0))
    (is (= (brutto {:netto 0 :vat 23}) 0.0))

    ; negative VAT, coz why not?
    (is (= (brutto {:netto 1000 :vat -23}) 770.0))))


(deftest test-parse-custom
  (testing "Check that the specified operators are allowed"
    (is (= (parse-custom {} '(+ 1 2)) 3))
    (is (= (parse-custom {} '(- 1 2)) -1))
    (is (= (parse-custom {} '(* 4 2)) 8))
    (is (= (parse-custom {} '(/ 13 2)) 13/2)))

  (testing "Check that non specified operators cause errors"
    (is (thrown? java.lang.IllegalArgumentException (parse-custom {} '(> 1 2))))
    (is (thrown? java.lang.IllegalArgumentException (parse-custom {} '(< 1 2))))
    (is (thrown? java.lang.IllegalArgumentException (parse-custom {} '(map 1 2)))))

  (testing "Check that worklog values get used"
    (is (= (parse-custom {:worked 12} '(+ :worked 2)) 14))
    (is (= (parse-custom {:required 32} '(- :required 2)) 30)))

  (testing "Check error raised if non worklog keys provided"
    (is (thrown? java.lang.IllegalArgumentException (parse-custom {} '(> :bla 2))))
    (is (thrown? java.lang.IllegalArgumentException (parse-custom {} '(> :non-worked 2))))))

(deftest test-calc-part-time
  (testing "Check whether calculating part time costs works"
    ; 4h per day, 10 per month if all hours done, the person did all of thier required hours
    (is (= (calc-part-time {:worked 12 :required 24} {:base 10 :per-day 4}) 10.0))

    ; 4h per day, 10 per month if all hours done, the person is 2 hours low
    (is (= (round (calc-part-time {:worked 10 :required 24} {:base 10 :per-day 4})) 8.33))

    ; 4h per day, 10 per month if all hours done, the person did 10h extra hours
    (is (= (round (calc-part-time {:worked 22 :required 24} {:base 10 :per-day 4})) 18.33))

    ; 8h per day, 10 per month if all hours done, the person did all required hours
    (is (= (round (calc-part-time {:worked 24 :required 24} {:base 10 :per-day 8})) 10.0)))

  (testing "Check whether nil is returned if :worked or :required missing are nil"
    (is (nil? (calc-part-time {:worked nil :required 24} {:base 10 :per-day 8})))
    (is (nil? (calc-part-time {:worked 24 :required nil} {:base 10 :per-day 8})))
    (is (nil? (calc-part-time {:worked nil :required nil} {:base 10 :per-day 8}))))

  (testing "Check whether nil is returned if :worked or :required missing"
    (is (nil? (calc-part-time {:required 24} {:base 10 :per-day 8})))
    (is (nil? (calc-part-time {:worked 24} {:base 10 :per-day 8})))
    (is (nil? (calc-part-time {} {:base 10 :per-day 8})))))

(deftest test-calc-hourly
  (testing "Check whether calculating hourly rates works"
    (is (= (calc-hourly {:worked 12} {:hourly 10}) 120))
    (is (= (calc-hourly {:worked 12.5} {:hourly 10}) 125.0))
    (is (= (calc-hourly {:worked 12} {:hourly 10.99}) 131.88)))

  (testing "nil is returned when no :worked provided"
    (is (nil? (calc-hourly {:worked nil} {:hourly 10})))
    (is (nil? (calc-hourly {} {:hourly 10})))))


(deftest test-calc-custom
  (testing "Check whether custom formulas work"
    ; base per hour
    (is (= (calc-custom {:worked 12} {:function '(* :worked 10)}) 120))
    ; Sylwia's formula
    (is (= (round (calc-custom {:worked 100 :required 168}
                               {:function '(+ 1000 (* (- :worked (/ :required 2)) (/ 2000 167)))}))
           1191.62))))


(deftest test-set-price
  (let [worked {:worked 100 :required 168}]
    (testing "Check the default is to set 0"
      (is (= (:netto (set-price worked {})) 0)))

    (testing "Check that :netto is returned if no calc func provided"
      (is (= (:netto (set-price worked {:netto 123})) 123)))

    (testing "Check that :per-day is ignored if :base not provided"
      (is (= (:netto (set-price worked {:per-day 123})) 0)))

    (testing "Check that part time is calculated if :base provided"
      (is (= (round (:netto (set-price worked {:base 100 :per-day 4}))) 119.05)))

    (testing "Check that per hour calculated if :hourly provided"
      (is (= (round (:netto (set-price worked {:hourly 10}))) 1000.0)))

    (testing "Check that custom func used if provided"
      (is (= (round (:netto (set-price worked {:function '(* :worked 12)}))) 1200.0)))))
