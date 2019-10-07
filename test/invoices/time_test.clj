(ns invoices.time-test
  (:require [clojure.test :refer :all]
            [invoices.time :refer :all]))


(deftest test-last-day
  (testing "getting last day of month"
    (doseq [[day last] [["2019-10-01" "2019-10-31"]
                        ["2019-06-28" "2019-06-30"]
                        ["2019-02-03" "2019-02-28"]
                        ["2020-02-13" "2020-02-29"]]]
      (is (= (last-day (java.time.LocalDate/parse day)) (java.time.LocalDate/parse last))))))


(deftest test-prev-month
  (testing "getting previous month"
    (doseq [[day previous] [["2019-11-01" "2019-10-01"]
                            ["2019-07-28" "2019-06-01"]
                            ["2019-03-03" "2019-02-01"]
                            ["2020-01-13" "2019-12-01"]]]
      (is (= (prev-month (java.time.LocalDate/parse day)) (java.time.LocalDate/parse previous))))))


(deftest test-skip-days-off
  (testing "Check that work days are returned as is"
    (doseq [day ["2019-10-04" ; friday
                 "2019-10-03" ; thursday
                 "2019-10-02"
                 "2019-10-01"
                 "2019-10-07"]] ; monday
      (is (= (skip-days-off (java.time.LocalDate/parse day)) (java.time.LocalDate/parse day)))))
  (testing "Check that weekends are skipped"
    (doseq [[day friday] [["2019-10-06" "2019-10-04"] ; a sunday
                          ["2019-10-05" "2019-10-04"]
                          ["2019-09-01" "2019-08-30"]]] ; month ends are correctly handled
      (is (= (skip-days-off (java.time.LocalDate/parse day)) (java.time.LocalDate/parse friday))))))


(deftest test-last-working-day
  (testing "Check that getting the last working day works"
    (doseq [[day last] [["2019-10-06" "2019-10-31"] ; skip forward to the end of the month
                        ["2019-08-31" "2019-08-30"]]] ; go back to the last working day
      (is (= (last-working-day (java.time.LocalDate/parse day)) (java.time.LocalDate/parse last))))))


(deftest test-date-applies
  (testing "Check that all dates apply when no to or from provided"
    (doseq [day ["1066-10-06" "2019-10-31" "2219-08-30"]]
      (is (date-applies? (java.time.LocalDate/parse day) {:to nil :from nil}))))

  (let [day (java.time.LocalDate/parse "2019-10-10")]
    (testing "Check that only dates before :to are valid"
      (is (date-applies? day {:to "2020-10-10" :from nil}))
      (is (date-applies? day {:to "2019-10-11" :from nil}))

      (is (not (date-applies? day {:to "2019-10-10" :from nil}))) ; the same day is deemed invalid
      (is (not (date-applies? day {:to "2010-10-10" :from nil}))))

    (testing "Check that only dates after :from are valid"
      (is (date-applies? day {:to nil :from "2000-10-10"}))
      (is (date-applies? day {:to nil :from "2019-10-09"}))
      (is (date-applies? day {:to nil :from "2019-10-10"})) ; the same day is deemed valid

      (is (not (date-applies? day {:to nil :from "2019-10-11"})))
      (is (not (date-applies? day {:to nil :from "2219-10-11"}))))

    (testing "Check that only dates between :to :from are valid"
      (is (date-applies? day {:to "2020-10-10" :from "2000-10-10"}))
      (is (date-applies? day {:to "2019-10-11" :from "2019-10-10"}))

      (is (not (date-applies? day {:to "2019-10-10" :from "2019-10-10"})))
      (is (not (date-applies? day {:to "2019-10-11" :from "2019-10-11"}))))

    (testing ":from must be before :to"
      (is (not (date-applies? day {:to "2019-10-12" :from "2020-10-10"})))
      (is (not (date-applies? day {:to "2018-10-10" :from "2020-10-10"}))))))
