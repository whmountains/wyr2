(defproject wyr2 "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [selmer "1.12.40"]
                 [gnl/ghostwheel "0.3.9"]
                 [expound "0.8.9"]
                 [org.clojure/test.check "0.10.0"]]
  :main ^:skip-aot wyr2.core
  :target-path "target/%s"
  :injections [(.. System (setProperty "ghostwheel.enabled" "true"))]
  :profiles {:uberjar {:aot :all}})
