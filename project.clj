(defproject qut-wsn "0.1.0-SNAPSHOT"
  :description "QUT Wireless Sensor Network"
  :url "http://github.com/lukecooper/qut-wsn"
  :license {:name "GNU General Public License, version 2"
            :url "http://www.gnu.org/licenses/gpl-2.0.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [com.rmoquin.bundle/jeromq "0.2.0"]                            
                 [clj-ssh "0.5.7"]
                 [clj-time "0.6.0"]
                 [antler/commons-io "2.2.0"]]
  :main qut-wsn.core
  :profiles {:uberjar {:aot :all}})
