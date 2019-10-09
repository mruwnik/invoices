(ns invoices.email-test
  (:require [invoices.email :refer :all]
            [clojure.test :refer :all]))


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
