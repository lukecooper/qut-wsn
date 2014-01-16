(defproject pewson "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [com.rmoquin.bundle/jeromq "0.2.0"]
                 [cheshire "5.2.0"]
                 [me.raynes/conch "0.5.0"]]
  :Main pewson.core
  :profiles {:uberjar {:aot :all}})
