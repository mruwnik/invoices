(ns test-runner
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- file->ns-sym [root file]
  (let [rel (-> (.toPath (io/file root))
                (.relativize (.toPath file))
                str)]
    (-> rel
        (str/replace #"\.clj$" "")
        (str/replace \_ \-)
        (str/replace \/ \.)
        symbol)))

(defn- discover-test-nses [root]
  (->> (file-seq (io/file root))
       (filter #(and (.isFile ^java.io.File %)
                     (str/ends-with? (.getName ^java.io.File %) "_test.clj")))
       (map #(file->ns-sym root %))
       sort))

(defn run [_]
  (let [nses (discover-test-nses "test")]
    (doseq [n nses] (require n))
    (let [{:keys [fail error]} (apply t/run-tests nses)]
      (System/exit (if (pos? (+ fail error)) 1 0)))))

(defn -main [& _]
  (run nil))
