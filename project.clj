(defproject jepsen.redis-sentinel "0.1.0-SNAPSHOT"
  :description "Jepsen tests for Redis Sentinel linearizability"
  :url "https://github.com/your-username/jepsen.redis-sentinel"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  ;; Core dependencies
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [jepsen "0.3.5"]
                 [com.taoensso/carmine "3.2.0"]
                 [cheshire "5.11.0"]
                 [clj-time "0.15.2"]
                 ;; Remove slf4j-simple, keep only logback
                 [org.slf4j/slf4j-api "1.7.36"]
                 [ch.qos.logback/logback-classic "1.2.11"]
                 [org.clojure/core.async "1.6.673"]
                 [org.clojure/tools.logging "1.2.4"]]

  ;; Ensure no conflicting logging dependencies
  :exclusions [org.slf4j/slf4j-log4j12 
               org.slf4j/slf4j-simple  ; Add this exclusion
               log4j/log4j]

  ;; Entry point
  :main ^:skip-aot jepsen.redis-sentinel.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[org.clojure/tools.namespace "1.4.4"]]}}

  ;; JVM tuning
  :jvm-opts ["-Djava.awt.headless=true"
             "-server"
             "-Xmx4g"
             "-XX:+UseG1GC"
             "-XX:MaxGCPauseMillis=50"
             "-Dlogback.configurationFile=resources/logback.xml"])  ; Fix path