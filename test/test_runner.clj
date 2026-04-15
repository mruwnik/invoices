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

(defn- check-tmpdir-writable
  "Pre-flight warning for the gotcha where java.io.tmpdir points at a
  read-only location (e.g. sandboxed runs that don't export a writable
  tmpdir to the JVM). Jakarta Mail's MimeMessage and any other code that
  calls File.createTempFile will throw opaque 'Read-only file system'
  errors that look like test regressions but are environmental. Fires
  once, before any namespace loads, so it's impossible to miss."
  []
  (let [tmp (System/getProperty "java.io.tmpdir")
        f   (io/file tmp)]
    (when-not (and (.exists f) (.canWrite f))
      (println
       (str "WARNING: java.io.tmpdir=" tmp
            " is not writable — Jakarta Mail and any code calling "
            "File.createTempFile will fail with 'Read-only file system'. "
            "Set _JAVA_OPTIONS=-Djava.io.tmpdir=/path/to/writable/dir "
            "before re-running.")))))

(defn run [_]
  (check-tmpdir-writable)
  (let [nses (discover-test-nses "test")]
    (doseq [n nses] (require n))
    (let [{:keys [fail error]} (apply t/run-tests nses)]
      (System/exit (if (pos? (+ fail error)) 1 0)))))

(defn -main [& _]
  (run nil))
