(defproject invoices "0.1.0-SNAPSHOT"
  :description "Generate invoices on the basis of a config file"
  :url "https://github.com/mruwnik/invoices"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/tools.cli "0.4.2"]
                 [com.draines/postal "2.0.3"]
                 [clj-http "3.10.0"]
                 [cheshire "5.9.0"]
                 [clj-pdf "2.4.0"]]
  :main ^:skip-aot invoices.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
