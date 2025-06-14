(ns jepsen.redis-sentinel.test-ssh
  (:require [clojure.tools.logging :refer :all]
            [jepsen [checker :as checker]
             [client :as client]
             [control :as c]
             [db :as db]
             [generator :as gen]
             [tests :as tests]
             [core :as jepsen]
             [nemesis :as nemesis]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.net :as net]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [knossos.model :as model]
            [taoensso.carmine :as car :refer [wcar]]))

(defn redis-db []
  (reify db/DB
    (setup! [_ test node]
      (info "Setting up Redis on" node)
      (c/exec :redis-cli :flushall))

    (teardown! [_ test node]
      (info "Cleaning up Redis on" node))))

(defn redis-client []
  (let [conn (atom nil)]
    (reify client/Client
      (open! [this test node]
        (reset! conn {:pool {} :spec {:host node :port 6379}})
        this)

      (setup! [this test])

      (invoke! [this test operation]
        (try
          (case (:f operation)
            :read
            (let [value (wcar @conn (car/get "test-key"))]
              (assoc operation :type :ok :value value))

            :write
            (do
              (wcar @conn (car/set "test-key" (:value operation)))
              (assoc operation :type :ok)))

          (catch Exception e
            (assoc operation :type :fail :error (.getMessage e)))))

      (teardown! [this test])
      (close! [this test]))))

(defn redis-sentinel-client []
  "Client that uses Redis Sentinel for primary/replica distinction"
  (let [conn (atom nil)
        primary-node (atom "n1")  ; Start with n1 as assumed primary
        replica-nodes (atom ["n2" "n3" "n4" "n5"])]
    (reify client/Client
      (open! [this test node]
        (reset! conn {:pool {} :spec {:host node :port 6379}})
        this)

      (setup! [this test]
        (info "Setting up Redis Sentinel client"))

      (invoke! [this test operation]
        (try
          (info "Received operation:" operation)
          (case (:f operation)
            :read
            ;; Read from any replica (round-robin through replicas)
            (let [replica (rand-nth @replica-nodes)
                  replica-conn {:pool {} :spec {:host replica :port 6379}}
                  value (wcar replica-conn (car/get "test-key"))]
              (info "Reading from replica" replica "value:" value)
              (assoc operation :type :ok :value value :node replica))

            :write
            ;; Write only to primary
            (let [primary @primary-node
                  primary-conn {:pool {} :spec {:host primary :port 6379}}]
              (wcar primary-conn (car/set "test-key" (:value operation)))
              (info "Writing to primary" primary "value:" (:value operation))
              (assoc operation :type :ok :node primary))

            ;; Handle unexpected operations
            (do
              (warn "Unknown operation type:" (:f operation))
              (assoc operation :type :fail :error (str "Unknown operation: " (:f operation)))))

          (catch Exception e
            (error "Operation failed:" (.getMessage e))
            (assoc operation :type :fail :error (.getMessage e)))))

      (teardown! [this test]
        (info "Tearing down Redis Sentinel client"))

      (close! [this test]
        (info "Closing Redis Sentinel client")))))

(defn simple-test []
  {:name "redis-simple-test"
   :os debian/os
   :db (redis-db)
   :client (redis-client)
   :nemesis nemesis/noop
   :concurrency 3
   :nodes ["n1" "n2" "n3" "n4" "n5"]
   ;; SSH configuration
   :remote c/ssh
   :username "root"
   :private-key-path "/root/.ssh/id_rsa"
   :strict-host-key-checking false
   :ssh-opts ["-o" "StrictHostKeyChecking=no"
              "-o" "UserKnownHostsFile=/dev/null"
              "-o" "GlobalKnownHostsFile=/dev/null"
              "-o" "LogLevel=ERROR"]
   ;; Generator with incrementing values
   :generator (let [counter (atom 0)
                    reads (gen/repeat {:type :invoke :f :read})
                    writes (->> (gen/repeat {:type :invoke :f :write})
                                (gen/map (fn [op] (assoc op :value (swap! counter inc)))))
                    client-ops (gen/mix [reads writes])]
                (->> client-ops
                     (gen/stagger 1/10)
                     (gen/nemesis
                      (cycle [(gen/sleep 5)
                              {:type :info, :f :start}
                              (gen/sleep 5)
                              {:type :info, :f :stop}]))
                     (gen/time-limit 30)))
   ;; Comprehensive checker configuration
   :checker (checker/compose
             {:stats (checker/stats)
              :perf (checker/perf {:nemeses? true
                                   :bandwidth? true
                                   :quantiles [0.5 0.95 0.99 0.999]})
              :latency-graph (checker/latency-graph {:nemeses? true
                                                     :subdirectory "latency"})
              :rate-graph (checker/rate-graph {:nemeses? true
                                               :subdirectory "rate"})
              :timeline (timeline/html)
              :linear (checker/linearizable {:model (model/register)
                                             :algorithm :linear})
              :clock-plot (checker/clock-plot)
              :unhandled-exceptions (checker/unhandled-exceptions)
              :log-file-pattern (checker/log-file-pattern #"ERROR|WARN|panic" "redis.log")})})

;; Fixed intensive-test with comprehensive checkers
(defn intensive-test []
  "More intensive test with primary/replica distinction for 3 minutes"
  {:name "redis-intensive-test"
   :os debian/os
   :db (redis-db)
   :client (redis-sentinel-client)
   :nemesis nemesis/noop
   :concurrency 15
   :nodes ["n1" "n2" "n3" "n4" "n5"]
   ;; SSH configuration
   :remote c/ssh
   :username "root"
   :private-key-path "/root/.ssh/id_rsa"
   :strict-host-key-checking false
   :ssh-opts ["-o" "StrictHostKeyChecking=no"
              "-o" "UserKnownHostsFile=/dev/null"
              "-o" "GlobalKnownHostsFile=/dev/null"
              "-o" "LogLevel=ERROR"]
   ;; Generator with incrementing values and 70/30 read/write ratio
   :generator (let [counter (atom 0)
                    reads (gen/repeat {:type :invoke :f :read})
                    writes (->> (gen/repeat {:type :invoke :f :write})
                                (gen/map (fn [op] (assoc op :value (swap! counter inc)))))
                    ;; Create 70/30 read/write mix
                    client-ops (->> [reads reads reads reads reads reads reads writes writes writes]
                                    (gen/mix)
                                    (gen/stagger 1/50))]
                (->> client-ops
                     (gen/nemesis
                      (cycle [(gen/sleep 10)
                              {:type :info, :f :start}
                              (gen/sleep 10)
                              {:type :info, :f :stop}]))
                     (gen/time-limit 180)))
   ;; Comprehensive checker configuration with all options
   :checker (checker/compose
             {:stats (checker/stats)
              :perf (checker/perf {:nemeses? true
                                   :bandwidth? true
                                   :quantiles [0.5 0.75 0.9 0.95 0.99 0.999]
                                   :subdirectory "perf"})
              :latency-graph (checker/latency-graph {:nemeses? true
                                                     :subdirectory "latency"
                                                     :quantiles [0.5 0.95 0.99 0.999]})
              :rate-graph (checker/rate-graph {:nemeses? true
                                               :subdirectory "rate"
                                               :quantiles [0.5 0.95 0.99]})
              :timeline (timeline/html)
              :linear-wgl (checker/linearizable {:model (model/register)
                                                 :algorithm :wgl})
              :linear-competition (checker/linearizable {:model (model/register)
                                                         :algorithm :linear})
              :clock-plot (checker/clock-plot)
              :unhandled-exceptions (checker/unhandled-exceptions)
              :log-errors (checker/log-file-pattern #"ERROR|FATAL|panic|exception" "redis.log")
              :log-warnings (checker/log-file-pattern #"WARN|warning" "redis.log")
              :counter (checker/counter)
              :set-analysis (checker/set)})})

;; Fixed intensive-test-concurrent with comprehensive checkers
(defn intensive-test-concurrent []
  "Intensive test using concurrent generator pattern for better key distribution"
  {:name "redis-intensive-concurrent-test"
   :os debian/os
   :db (redis-db)
   :client (redis-sentinel-client)
   :nemesis nemesis/noop
   :concurrency 15
   :nodes ["n1" "n2" "n3" "n4" "n5"]
   ;; SSH configuration
   :remote c/ssh
   :username "root"
   :private-key-path "/root/.ssh/id_rsa"
   :strict-host-key-checking false
   :ssh-opts ["-o" "StrictHostKeyChecking=no"
              "-o" "UserKnownHostsFile=/dev/null"
              "-o" "GlobalKnownHostsFile=/dev/null"
              "-o" "LogLevel=ERROR"]
   ;; Generator with incrementing values
   :generator (let [counter (atom 0)
                    reads (gen/repeat {:type :invoke :f :read})
                    writes (->> (gen/repeat {:type :invoke :f :write})
                                (gen/map (fn [op] (assoc op :value (swap! counter inc)))))
                    opts {:rate 50}
                    client-ops (gen/mix [reads writes])]
                (->> client-ops
                     (gen/stagger (/ (:rate opts)))
                     (gen/nemesis
                      (cycle [(gen/sleep 5)
                              {:type :info, :f :start}
                              (gen/sleep 5)
                              {:type :info, :f :stop}]))
                     (gen/time-limit 180)))
   ;; Most comprehensive checker configuration with concurrency limits
   :checker (checker/compose
             {:stats (checker/stats)
              :perf (checker/concurrency-limit
                     2
                     (checker/perf {:nemeses? true
                                    :bandwidth? true
                                    :quantiles [0.25 0.5 0.75 0.9 0.95 0.99 0.999]
                                    :subdirectory "perf-analysis"}))
              :latency-detailed (checker/concurrency-limit
                                 1
                                 (checker/latency-graph {:nemeses? true
                                                         :subdirectory "latency-detailed"
                                                         :quantiles [0.1 0.25 0.5 0.75 0.9 0.95 0.99 0.999]}))
              :rate-detailed (checker/rate-graph {:nemeses? true
                                                  :subdirectory "rate-detailed"
                                                  :quantiles [0.25 0.5 0.75 0.9 0.95 0.99]})
              :timeline (timeline/html)
              :linear-wgl (checker/concurrency-limit
                           1
                           (checker/linearizable {:model (model/register)
                                                  :algorithm :wgl}))
              :linear-competition (checker/linearizable {:model (model/register)
                                                         :algorithm :linear})
              :clock-analysis (checker/clock-plot)
              :exceptions (checker/unhandled-exceptions)
              :error-logs (checker/log-file-pattern #"ERROR|FATAL|panic|exception|fail" "redis.log")
              :warning-logs (checker/log-file-pattern #"WARN|warning" "redis.log")
              :debug-logs (checker/log-file-pattern #"DEBUG" "redis.log")
              :counter-analysis (checker/counter)
              :set-operations (checker/set)
              :set-full-analysis (checker/set-full {:linearizable? true})
              :optimistic (checker/unbridled-optimism)
              :noop-check (checker/noop)})})

(defn run-simple-test []
  "Run the simple 30-second test"
  (info "🚀 Starting simple Redis test for 30 seconds...")
  (info "📊 Expected ~900 operations (3 threads × 10 ops/sec × 30 sec)")
  (jepsen/run! (simple-test)))

(defn run-intensive-test []
  "Run the intensive 3-minute test"
  (info "🚀 Starting intensive Redis test for 3 minutes...")
  (info "📊 Expected ~135,000 operations (15 threads × 50 ops/sec × 180 sec)")
  (jepsen/run! (intensive-test)))

(defn run-intensive-concurrent-test []
  "Run the intensive concurrent test for 3 minutes"
  (info "🚀 Starting intensive concurrent Redis test for 3 minutes...")
  (jepsen/run! (intensive-test-concurrent)))

(defn -main [& args]
  ;; Set up SSH configuration globally with explicit host key bypass
  (c/with-ssh {:username "root"
               :private-key-path "/root/.ssh/id_rsa"
               :strict-host-key-checking false
               :ssh-opts ["-o" "StrictHostKeyChecking=no"
                          "-o" "UserKnownHostsFile=/dev/null"
                          "-o" "GlobalKnownHostsFile=/dev/null"
                          "-o" "LogLevel=ERROR"]}
    ;; Optional: Run a sanity check first
    (info "✅ Verifying SSH connectivity before starting test")
    (doseq [node ["n1" "n2" "n3" "n4" "n5"]]
      (try
        (c/on node
              (info "Uptime from" node ": " (c/exec :uptime)))
        (catch Exception e
          (error "Failed to connect to" node ":" (.getMessage e)))))

    ;; Choose which test to run based on command line argument
    (let [test-type (first args)]
      (case test-type
        "simple" (run-simple-test)
        "intensive" (run-intensive-test)
        "concurrent" (run-intensive-concurrent-test)
        ;; Default: run simple test only
        (do
          (info "🎯 Running simple test (use 'simple', 'intensive', or 'concurrent' for specific tests)")
          (run-simple-test))))))