(defproject jepsen-redis "0.1.0-SNAPSHOT"
  :description "Jepsen test for Redis with Sentinel failover"
  :url "https://github.com/jepsen-io/jepsen"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.3"]          ; Updated from 1.5.1
                 [jepsen "0.3.9"]                        ; Updated from 0.0.2-SNAPSHOT
                 [com.taoensso/carmine "2.19.1"]  ; This version has better compatibility with older Sentinel setups
                 [clj-time "0.15.2"]                     ; Updated from 0.5.1
                 [org.clojure/tools.logging "1.2.4"]     ; Updated from 0.2.6
                 [org.clojure/data.json "2.4.0"]]        ; Updated from 0.2.2
  :main jepsen.redis
  :aliases {"local" ["run" "-m" "jepsen.redis-local"]}
  :profiles {:uberjar {:aot :all}})