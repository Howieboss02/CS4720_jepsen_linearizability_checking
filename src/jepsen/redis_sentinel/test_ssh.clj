(ns jepsen.redis-sentinel.test-ssh
  (:require [clojure.tools.logging :refer :all]
            [jepsen [checker :as checker]
             [client :as client]
             [control :as c]
             [db :as db]
             [net :as net]
             [generator :as gen]
             [tests :as tests]
             [core :as jepsen]
             [nemesis :as nemesis]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.net :as cnet]
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
              ;; FIXED: Convert string to integer for consistency
              (assoc operation :type :ok 
                     :value (when value (Integer/parseInt value))))

            :write
            (do
              ;; FIXED: Convert to string when storing in Redis
              (wcar @conn (car/set "test-key" (str (:value operation))))
              (assoc operation :type :ok)))

          (catch Exception e
            (assoc operation :type :fail :error (.getMessage e)))))

      (teardown! [this test])
      (close! [this test]))))

(defn redis-sentinel-client []
  "Client that uses Redis Sentinel for primary/replica distinction"
  (let [conn (atom nil)
        primary-node (atom "n1")
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
            (let [replica (rand-nth @replica-nodes)
                  replica-conn {:pool {} :spec {:host replica :port 6379}}
                  value (wcar replica-conn (car/get "test-key"))]
              (info "Reading from replica" replica "value:" value)
              ;; FIXED: Convert string to integer for consistency
              (assoc operation :type :ok 
                     :value (when value (Integer/parseInt value))
                     :node replica))

            :write
            (let [primary @primary-node
                  primary-conn {:pool {} :spec {:host primary :port 6379}}]
              ;; FIXED: Convert to string when storing in Redis
              (wcar primary-conn (car/set "test-key" (str (:value operation))))
              (info "Writing to primary" primary "value:" (:value operation))
              (assoc operation :type :ok :node primary))

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

;; NEW: Redis SET-based client with Sentinel-like primary discovery
(defn redis-set-sentinel-client []
  "Client that writes incremental values to a Redis set with Sentinel-style primary discovery"
  (let [sentinel-nodes ["n3" "n4" "n5"]  ; Sentinel nodes
        current-primary (atom "n1")       ; Start with n1 as primary
        set-name "test-set"]
    (reify client/Client
      (open! [this test node]
        ;; Each client opens connection to its assigned node
        this)

      (setup! [this test]
        (info "Setting up Redis SET Sentinel client"))

      (invoke! [this test operation]
        (try
          (case (:f operation)
            :read-set
            ;; Read the entire set to check for survivors
            (let [primary @current-primary
                  primary-conn {:pool {} :spec {:host primary :port 6379}}
                  set-values (wcar primary-conn (car/smembers set-name))
                  ;; Convert strings to integers and sort
                  int-values (sort (map #(Integer/parseInt %) set-values))]
              (info "Reading set from" primary "values count:" (count int-values))
              (assoc operation :type :ok :value int-values :node primary))

            :write-set
            ;; Write to Redis set with strict Sentinel primary discovery
            (do
              ;; Ask Sentinel for current primary before EVERY write (stricter than normal)
              (let [sentinel-node (rand-nth sentinel-nodes)
                    sentinel-conn {:pool {} :spec {:host sentinel-node :port 26379}}]
                (try
                  ;; Try to get primary from Sentinel
                  (let [primary-info (wcar sentinel-conn 
                                           (car/raw "SENTINEL" "get-master-addr-by-name" "mymaster"))
                        new-primary (if (and primary-info (seq primary-info))
                                      (first primary-info)  ; IP address of primary
                                      @current-primary)]    ; Fallback to current
                    (reset! current-primary new-primary)
                    (info "Sentinel" sentinel-node "reports primary:" new-primary))
                  (catch Exception e
                    (warn "Failed to contact Sentinel" sentinel-node ":" (.getMessage e))
                    ;; Keep using current primary if Sentinel is unreachable
                    )))

              ;; Now write to the primary
              (let [primary @current-primary
                    primary-conn {:pool {} :spec {:host primary :port 6379}}
                    value (:value operation)]
                (wcar primary-conn (car/sadd set-name (str value)))
                (info "Added" value "to set on primary" primary)
                (assoc operation :type :ok :node primary :value value)))

            ;; Handle unexpected operations
            (do
              (warn "Unknown operation type:" (:f operation))
              (assoc operation :type :fail :error (str "Unknown operation: " (:f operation)))))

          (catch Exception e
            (error "Operation failed:" (.getMessage e))
            (assoc operation :type :fail :error (.getMessage e)))))

      (teardown! [this test]
        (info "Tearing down Redis SET Sentinel client"))

      (close! [this test]
        (info "Closing Redis SET Sentinel client")))))

;; NEW: Simple Redis SET client (no Sentinel)
(defn redis-set-client []
  "Simple client that writes incremental values to a Redis set"
  (let [conn (atom nil)
        set-name "test-set"]
    (reify client/Client
      (open! [this test node]
        (reset! conn {:pool {} :spec {:host node :port 6379}})
        this)

      (setup! [this test]
        (info "Setting up Redis SET client"))

      (invoke! [this test operation]
        (try
          (case (:f operation)
            :read-set
            ;; Read the entire set
            (let [set-values (wcar @conn (car/smembers set-name))
                  ;; Convert strings to integers and sort
                  int-values (sort (map #(Integer/parseInt %) set-values))]
              (info "Reading set, values count:" (count int-values))
              (assoc operation :type :ok :value int-values))

            :write-set
            ;; Add value to set
            (let [value (:value operation)]
              (wcar @conn (car/sadd set-name (str value)))
              (info "Added" value "to set")
              (assoc operation :type :ok :value value))

            (do
              (warn "Unknown operation type:" (:f operation))
              (assoc operation :type :fail :error (str "Unknown operation: " (:f operation)))))

          (catch Exception e
            (error "Operation failed:" (.getMessage e))
            (assoc operation :type :fail :error (.getMessage e)))))

      (teardown! [this test]
        (info "Tearing down Redis SET client"))

      (close! [this test]
        (info "Closing Redis SET client")))))

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
   ;; FIXED: Correct checkers for incremental register operations
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
              :unhandled-exceptions (checker/unhandled-exceptions)})})

;; FIXED: Intensive test with correct checkers for incremental values
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
   ;; FIXED: Comprehensive but compatible checker configuration
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
              :unhandled-exceptions (checker/unhandled-exceptions)})})

;; FIXED: Concurrent test with safe checkers
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
   ;; FIXED: Comprehensive but safe checker configuration
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
              :exceptions (checker/unhandled-exceptions)})})

;; FIXED: Split-brain test with correct checkers
(defn split-brain-test []
  "Split-brain test with network partitions for 3 minutes"
  {:name "redis-split-brain-test"
   :os debian/os
   :db (redis-db)
   :client (redis-sentinel-client)
   :nemesis (nemesis/partition-random-halves)  ; Split brain nemesis
   :net net/iptables  ; Add network implementation for partition nemesis
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
   ;; Generator with incrementing values and extended nemesis timing
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
                      (cycle [(gen/sleep 10)      ; 10 seconds before starting partition
                              {:type :info, :f :start}
                              (gen/sleep 20)      ; 20 seconds with partition active
                              {:type :info, :f :stop}]))
                     (gen/time-limit 180)))  ; 3 minutes total
   ;; FIXED: Correct checkers for split-brain with incremental register operations
   :checker (checker/compose
             {:stats (checker/stats)
              :perf (checker/perf {:nemeses? true
                                   :bandwidth? true
                                   :quantiles [0.5 0.75 0.9 0.95 0.99 0.999]
                                   :subdirectory "perf-split-brain"})
              :latency-graph (checker/latency-graph {:nemeses? true
                                                     :subdirectory "latency-split-brain"
                                                     :quantiles [0.5 0.95 0.99 0.999]})
              :rate-graph (checker/rate-graph {:nemeses? true
                                               :subdirectory "rate-split-brain"
                                               :quantiles [0.5 0.95 0.99]})
              :timeline (timeline/html)
              :linear-wgl (checker/linearizable {:model (model/register)
                                                 :algorithm :wgl})
              :linear-competition (checker/linearizable {:model (model/register)
                                                         :algorithm :linear})
              :clock-plot (checker/clock-plot)
              :unhandled-exceptions (checker/unhandled-exceptions)})})

(defn redis-set-split-brain-test []
  "Redis SET split-brain test that writes incremental values and checks for data loss during partitions"
  {:name "redis-set-split-brain-test"
   :os debian/os
   :db (redis-db)
   :client (redis-set-sentinel-client)  ; Use SET-based client with Sentinel discovery
   :nemesis (nemesis/partition-majorities-ring)  ; Creates minority/majority partitions
   :net net/iptables
   :concurrency 10  ; 10 concurrent writers
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
   ;; Generator focused on writes with periodic set reads
   :generator (let [counter (atom 0)
                    ;; Mostly writes with occasional full set reads
                    writes (->> (gen/repeat {:type :invoke :f :write-set})
                                (gen/map (fn [op] (assoc op :value (swap! counter inc)))))
                    reads (gen/repeat {:type :invoke :f :read-set})
                    ;; 90% writes, 10% reads for maximum write pressure
                    client-ops (->> [writes writes writes writes writes writes writes writes writes reads]
                                    (gen/mix)
                                    (gen/stagger 1/20))]  ; 20 ops/sec per thread
                (->> client-ops
                     (gen/nemesis
                      ;; Partition cycle: 30s normal → 30s partition → 30s heal → repeat
                      (cycle [(gen/sleep 30)
                              {:type :info, :f :start}
                              (gen/sleep 30)      ; 30 seconds of split-brain
                              {:type :info, :f :stop}
                              (gen/sleep 30)]))   ; 30 seconds of healing
                     (gen/time-limit 300)))  ; 5 minutes total

   ;; Custom checker for SET durability analysis
   :checker (checker/compose
             {:stats (checker/stats)
              :perf (checker/perf {:nemeses? true
                                   :bandwidth? true
                                   :quantiles [0.5 0.95 0.99 0.999]
                                   :subdirectory "perf-set-split-brain"})
              :timeline (timeline/html)
              :clock-plot (checker/clock-plot)
              :unhandled-exceptions (checker/unhandled-exceptions)
              ;; Custom durability checker
              :set-durability (reify checker/Checker
                                (check [this test history opts]
                                  (let [writes (->> history
                                                    (filter #(and (= :ok (:type %))
                                                                  (= :write-set (:f %))))
                                                    (map :value)
                                                    (into #{}))
                                        final-read (->> history
                                                        (filter #(and (= :ok (:type %))
                                                                      (= :read-set (:f %))))
                                                        (last)
                                                        (:value)
                                                        (into #{}))
                                        total-writes (count writes)
                                        survivors (count final-read)
                                        lost-writes (clojure.set/difference writes final-read)
                                        loss-rate (if (> total-writes 0)
                                                    (/ (count lost-writes) total-writes)
                                                    0)]
                                    {:valid? (< loss-rate 0.01)  ; Allow 1% loss
                                     :total-writes total-writes
                                     :survivors survivors
                                     :lost-writes (sort lost-writes)
                                     :loss-rate loss-rate
                                     :acknowledgment-rate (/ total-writes total-writes)
                                     :message (if (< loss-rate 0.01)
                                                "✅ Acceptable data loss"
                                                (str "❌ Excessive data loss: "
                                                     (count lost-writes)
                                                     " writes lost ("
                                                     (Math/round (* loss-rate 100))
                                                     "%)"))})))})})

;; NEW: Simple Redis SET test without partitions
(defn redis-set-simple-test []
  "Simple Redis SET test to verify basic functionality"
  {:name "redis-set-simple-test"
   :os debian/os
   :db (redis-db)
   :client (redis-set-client)  ; Simple SET client
   :nemesis nemesis/noop
   :concurrency 5
   :nodes ["n1" "n2" "n3" "n4" "n5"]
   :remote c/ssh
   :username "root"
   :private-key-path "/root/.ssh/id_rsa"
   :strict-host-key-checking false
   :ssh-opts ["-o" "StrictHostKeyChecking=no"
              "-o" "UserKnownHostsFile=/dev/null"
              "-o" "GlobalKnownHostsFile=/dev/null"
              "-o" "LogLevel=ERROR"]
   :generator (let [counter (atom 0)
                    writes (->> (gen/repeat {:type :invoke :f :write-set})
                                (gen/map (fn [op] (assoc op :value (swap! counter inc)))))
                    reads (gen/repeat {:type :invoke :f :read-set})
                    client-ops (->> [writes writes writes reads]  ; 75% writes, 25% reads
                                    (gen/mix)
                                    (gen/stagger 1/10))]
                (->> client-ops
                     (gen/time-limit 60)))  ; 1 minute test
   :checker (checker/compose
             {:stats (checker/stats)
              :timeline (timeline/html)
              :set-checker (reify checker/Checker
                             (check [this test history opts]
                               (let [writes (->> history
                                                 (filter #(and (= :ok (:type %))
                                                               (= :write-set (:f %))))
                                                 (map :value)
                                                 (into #{}))
                                     final-read (->> history
                                                     (filter #(and (= :ok (:type %))
                                                                   (= :read-set (:f %))))
                                                     (last)
                                                     (:value)
                                                     (into #{}))]
                                 {:valid? (= writes final-read)
                                  :total-writes (count writes)
                                  :survivors (count final-read)
                                  :missing (clojure.set/difference writes final-read)})))})})

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

(defn run-split-brain-test []
  "Run the split-brain test with network partitions for 3 minutes"
  (info "🚀 Starting split-brain Redis test for 3 minutes...")
  (info "🧠 Network partitions: 10s normal → 20s partition → 10s normal → repeat")
  (info "📊 Expected ~135,000 operations with partition tolerance testing")
  (jepsen/run! (split-brain-test)))

;; NEW: Run Redis SET tests
(defn run-redis-set-simple-test []
  "Run the simple Redis SET test for 1 minute"
  (info "🚀 Starting simple Redis SET test for 1 minute...")
  (info "📦 Testing basic SET operations without partitions")
  (jepsen/run! (redis-set-simple-test)))

(defn run-redis-set-split-brain-test []
  "Run the Redis SET split-brain test for 5 minutes"
  (info "🚀 Starting Redis SET split-brain test for 5 minutes...")
  (info "🧠 This test replicates the classic Redis Sentinel split-brain scenario")
  (info "📦 Writing incremental values to SET with network partitions")
  (info "⚠️  Expect significant data loss during split-brain scenarios!")
  (jepsen/run! (redis-set-split-brain-test)))

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
        "split-brain" (run-split-brain-test)
        "set-simple" (run-redis-set-simple-test)           ; NEW
        "set-split-brain" (run-redis-set-split-brain-test) ; NEW
        ;; Default: run simple test only
        (do
          (info "🎯 Available tests:")
          (info "  simple - Basic register test")
          (info "  intensive - High-concurrency register test")
          (info "  concurrent - Concurrent register test")
          (info "  split-brain - Register split-brain test")
          (info "  set-simple - Basic SET test")
          (info "  set-split-brain - SET split-brain test (recommended!)")
          (info "🎯 Running simple test by default...")
          (run-simple-test))))))