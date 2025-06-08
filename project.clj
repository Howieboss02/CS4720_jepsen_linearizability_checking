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
                 [clj-time "0.15.2"]]

  ;; Entry point
  :main ^:skip-aot jepsen.redis-sentinel
  :aot [jepsen.redis-sentinel]

  ;; Source path setup
  :source-paths ["src"]

  ;; JVM tuning
  :jvm-opts ["-Djava.awt.headless=true"
             "-server"
             "-Xmx4g"
             "-XX:+UseG1GC"
             "-XX:MaxGCPauseMillis=50"])
