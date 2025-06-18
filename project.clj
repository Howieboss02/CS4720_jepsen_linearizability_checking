(defproject jepsen.redis-sentinel "0.1.0-SNAPSHOT"
  :description "Jepsen tests for Redis Sentinel linearizability"
  :url "https://github.com/your-username/jepsen.redis-sentinel"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  ;; Core dependencies - only verified, existing artifacts
  :dependencies [
                 ;; Core Clojure
                 [org.clojure/clojure "1.11.1"]
                 
                 ;; Jepsen testing framework
                 [jepsen "0.3.5"]
                 
                 ;; Redis client
                 [com.taoensso/carmine "3.2.0"]
                 
                 ;; JSON parsing
                 [cheshire "5.11.0"]
                 [org.clojure/data.json "2.4.0"]
                 
                 ;; Time handling
                 [clj-time "0.15.2"]
                 
                 ;; Logging
                 [org.slf4j/slf4j-api "1.7.36"]
                 [ch.qos.logback/logback-classic "1.2.12"]
                 [ch.qos.logback/logback-core "1.2.12"]
                 [org.clojure/tools.logging "1.2.4"]
                 
                 ;; Async operations
                 [org.clojure/core.async "1.6.673"]
                 
                 ;; HTTP client for REST APIs
                 [clj-http "3.12.3"]
                 [http-kit "2.7.0"]
                 
                 ;; CLI and configuration
                 [org.clojure/tools.cli "1.0.219"]
                 [environ "1.2.0"]
                 
                 ;; Data structures and utilities
                 [org.clojure/data.codec "0.1.1"]
                 [org.clojure/data.csv "1.0.1"]
                 [org.clojure/core.cache "1.0.225"]
                 [org.clojure/core.memoize "1.0.257"]
                 
                 ;; SSH and system operations
                 [com.jcraft/jsch "0.1.55"]
                 [clj-commons/clj-ssh "0.5.15"]
                 
                 ;; Network utilities
                 [aleph "0.6.3"]
                 [manifold "0.4.0"]
                 
                 ;; Statistical analysis
                 [org.clojure/math.numeric-tower "0.0.5"]
                 [incanter/incanter-core "1.9.3"]
                 [incanter/incanter-charts "1.9.3"]
                 
                 ;; XML/HTML parsing (for monitoring)
                 [enlive "1.1.6"]
                 [hickory "0.7.1"]
                 
                 ;; Process control
                 [me.raynes/conch "0.8.0"]
                 
                 ;; Error handling
                 [slingshot "0.12.2"]
                 [dire "0.5.4"]
                 
                 ;; Database connections (if needed for monitoring)
                 [org.clojure/java.jdbc "0.7.12"]
                 
                 ;; Spec for validation
                 [org.clojure/spec.alpha "0.3.218"]
                 [org.clojure/test.check "1.1.1"]
                 
                 ;; Metrics and monitoring
                 [metrics-clojure "2.10.0"]
                 [com.climate/claypoole "1.1.4"]
                 
                 ;; File I/O
                 [org.clojure/data.fressian "1.0.0"]
                 [com.taoensso/nippy "3.2.0"]
                 
                 ;; UUID generation
                 [danlentz/clj-uuid "0.1.9"]
                 
                 ;; Pretty printing
                 [fipp "0.6.26"]
                 [mvxcvi/puget "1.3.4"]  ; Fixed artifact coordinates
                 
                 ;; Additional Redis clients (for comparison)
                 [redis.clients/jedis "4.3.1"]
                 
                 ;; Configuration management
                 [aero "1.1.6"]
                 [cprop "0.1.19"]
                 
                 ;; Test utilities
                 [org.clojure/test.generative "1.1.0"]
                 [criterium "0.4.6"]
                 ]

  ;; Ensure no conflicting logging dependencies
  :exclusions [org.slf4j/slf4j-log4j12 
               org.slf4j/slf4j-simple
               log4j/log4j
               commons-logging/commons-logging]

  ;; Entry point
  :main ^:skip-aot jepsen.redis-sentinel.core
  :target-path "target/%s"
  
  ;; Profiles for different environments
  :profiles {
             :uberjar {:aot :all
                      :omit-source true
                      :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
             
             :dev {:dependencies [[org.clojure/tools.namespace "1.4.4"]
                                 [org.clojure/tools.trace "0.7.11"]
                                 [clj-stacktrace "0.2.8"]
                                 [proto-repl "0.3.1"]]
                  :source-paths ["dev"]
                  :repl-options {:init-ns user
                                :welcome (println "Redis Sentinel Jepsen Test Suite")}}
             
             :test {:dependencies [[org.clojure/test.check "1.1.1"]
                                  [com.gfredericks/test.chuck "0.2.13"]]
                   :jvm-opts ["-Djepsen.logback.appender=console"]}
             
             :bench {:dependencies [[criterium "0.4.6"]]
                    :jvm-opts ["-server" "-Xmx8g"]}
             }

  ;; Repositories for additional dependencies
  :repositories [["central" {:url "https://repo1.maven.org/maven2/" :snapshots false}]
                ["clojars" {:url "https://clojars.org/repo/"}]]

  ;; JVM tuning for Jepsen tests
  :jvm-opts ["-Djava.awt.headless=true"
             "-server"
             "-Xmx4g"
             "-Xms1g"
             "-XX:+UseG1GC"
             "-XX:MaxGCPauseMillis=50"
             "-Djepsen.logback.appender=file"
             "-Dlogback.configurationFile=resources/logback.xml"
             "-Djava.security.egd=file:/dev/./urandom"
             "-Dclojure.tools.logging.factory=clojure.tools.logging.impl/slf4j-factory"]

  ;; Resource paths
  :resource-paths ["resources"]
  
  ;; AOT compilation settings
  :aot [jepsen.redis-sentinel.core]
  
  ;; Global exclusions to avoid conflicts
  :global-vars {*warn-on-reflection* true
                *unchecked-math* :warn-on-boxed}
  
  ;; Test configuration
  :test-selectors {:default (complement :integration)
                  :integration :integration
                  :unit (complement :integration)
                  :fast (complement :slow)
                  :slow :slow}

  ;; Aliases for common tasks
  :aliases {"test-all" ["do" "clean," "test"]
           "format" ["cljfmt" "fix"]
           "jepsen-test" ["run" "--test-name" "linearizability"]
           "quick-test" ["run" "--test-name" "linearizability" "--time-limit" "60"]})