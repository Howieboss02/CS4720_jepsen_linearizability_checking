(ns jepsen.redis-sentinel.test-ssh
  (:require [clojure.tools.logging :refer :all]
            [jepsen.checker :as checker]
            [jepsen.client :as client]
            [jepsen.control :as c]
            [jepsen.db :as db]
            [jepsen.net :as net]
            [jepsen.generator :as gen]
            [jepsen.tests :as tests]
            [jepsen.core :as jepsen]
            [jepsen.nemesis :as nemesis]
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

(defn redis-sentinel-client []
  (let [conn (atom nil)
        sentinel-nodes ["n1" "n2" "n3" "n4" "n5"]
        current-primary (atom "n1")
        replica-nodes (atom ["n2" "n3" "n4" "n5"])
        ip-to-hostname {"172.20.0.11" "n1"
                        "172.20.0.12" "n2"
                        "172.20.0.13" "n3"
                        "172.20.0.14" "n4"
                        "172.20.0.15" "n5"}]
    (reify client/Client
      (open! [this test node]
        (reset! conn {:pool {} :spec {:host node :port 6379}})
        this)
      (setup! [this test]
        (info "Setting up Redis Sentinel client"))
      (invoke! [this test operation]
        (try
          (case (:f operation)
            :read
            (let [replica (rand-nth @replica-nodes)
                  replica-conn {:pool {} :spec {:host replica :port 6379}}
                  value (wcar replica-conn (car/get "test-key"))]
              (info "Reading from replica" replica "value:" value)
              (assoc operation :type :ok
                     :value (when value (Integer/parseInt value))
                     :node replica))
            :write
            (do
              (let [sentinel-node (rand-nth sentinel-nodes)]
                (try
                  (let [sentinel-conn {:pool {} :spec {:host sentinel-node :port 26379}}
                        ;; Fixed: Use vector of strings for redis-call arguments
                        primary-info (wcar sentinel-conn
                                           (car/redis-call ["SENTINEL" "get-master-addr-by-name" "mymaster"]))]
                    (info "Raw Sentinel response from" sentinel-node ":" primary-info
                          "Type:" (type primary-info))

                    ;; Handle different response types properly
                    (when primary-info
                      (let [parsed-info (cond
                                          ;; If it's a vector/list with IP and port
                                          (and (sequential? primary-info)
                                               (>= (count primary-info) 2))
                                          primary-info

                                          ;; If it's nested (vector of vectors)
                                          (and (sequential? primary-info)
                                               (= (count primary-info) 1)
                                               (sequential? (first primary-info)))
                                          (first primary-info)

                                          ;; Otherwise use as-is
                                          :else
                                          primary-info)]

                        (info "Parsed Sentinel info:" parsed-info)

                        (when (and (sequential? parsed-info)
                                   (>= (count parsed-info) 2))
                          (let [primary-ip (str (first parsed-info))
                                new-primary (get ip-to-hostname primary-ip primary-ip)]
                            (reset! current-primary new-primary)
                            (info "Sentinel" sentinel-node "reports primary:" new-primary "(" primary-ip ")"))))))
                  (catch Exception e
                    (warn "Failed to contact Sentinel" sentinel-node ":" (.getMessage e)))))

              (let [primary @current-primary
                    primary-conn {:pool {} :spec {:host primary :port 6379}}]
                (wcar primary-conn (car/set "test-key" (str (:value operation))))
                (info "Writing to primary" primary "value:" (:value operation))
                (assoc operation :type :ok :node primary)))

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

(defn redis-set-sentinel-client []
  (let [sentinel-nodes ["n1" "n2" "n3" "n4" "n5"]
        current-primary (atom "n1")
        set-name "test-set"
        ip-to-hostname {"172.20.0.11" "n1"
                        "172.20.0.12" "n2"
                        "172.20.0.13" "n3"
                        "172.20.0.14" "n4"
                        "172.20.0.15" "n5"}]
    (reify client/Client
      (open! [this test node]
        this)
      (setup! [this test]
        (info "Setting up Redis SET Sentinel client"))
      (invoke! [this test operation]
        (try
          (case (:f operation)
            :read-set-all
            (let [all-nodes ["n1" "n2" "n3" "n4" "n5"]
                  results (atom {})]
              (doseq [node all-nodes]
                (try
                  (let [conn {:pool {} :spec {:host node :port 6379}}
                        set-values (wcar conn (car/smembers set-name))
                        int-values (sort (map #(Integer/parseInt %) set-values))]
                    (swap! results assoc node int-values)
                    (info "Read from" node ":" (count int-values) "values"))
                  (catch Exception e
                    (info "Failed to read from" node ":" (.getMessage e))
                    (swap! results assoc node :unreachable))))
              (assoc operation :type :ok :value @results))

            :write-set
            (do
              (let [sentinel-node (rand-nth sentinel-nodes)]
                (try
                  (let [sentinel-conn {:pool {} :spec {:host sentinel-node :port 26379}}
                        ;; Fixed: Use vector of strings for redis-call arguments
                        primary-info (wcar sentinel-conn
                                           (car/redis-call ["SENTINEL" "get-master-addr-by-name" "mymaster"]))]
                    (info "Raw Sentinel response from" sentinel-node ":" primary-info
                          "Type:" (type primary-info))

                    ;; Handle different response types properly
                    (when primary-info
                      (let [parsed-info (cond
                                          ;; If it's a vector/list with IP and port
                                          (and (sequential? primary-info)
                                               (>= (count primary-info) 2))
                                          primary-info

                                          ;; If it's nested (vector of vectors)
                                          (and (sequential? primary-info)
                                               (= (count primary-info) 1)
                                               (sequential? (first primary-info)))
                                          (first primary-info)

                                          ;; Otherwise use as-is
                                          :else
                                          primary-info)]

                        (info "Parsed Sentinel info:" parsed-info)

                        (when (and (sequential? parsed-info)
                                   (>= (count parsed-info) 2))
                          (let [primary-ip (str (first parsed-info))
                                new-primary (get ip-to-hostname primary-ip primary-ip)]
                            (reset! current-primary new-primary)
                            (info "Sentinel" sentinel-node "reports primary:" new-primary "(" primary-ip ")"))))))
                  (catch Exception e
                    (warn "Failed to contact Sentinel" sentinel-node ":" (.getMessage e)))))

              (let [primary @current-primary
                    primary-conn {:pool {} :spec {:host primary :port 6379}}
                    value (:value operation)]
                (wcar primary-conn (car/sadd set-name (str value)))
                (info "Added" value "to set on primary" primary)
                (assoc operation :type :ok :node primary :value value)))

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

(defn simple-test []
  {:name "redis-simple-test"
   :os debian/os
   :db (redis-db)
   :client (redis-sentinel-client)
   :nemesis nemesis/noop
   :concurrency 3
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

(defn intensive-test []
  "More intensive test with primary/replica distinction for 3 minutes"
  {:name "redis-intensive-test"
   :os debian/os
   :db (redis-db)
   :client (redis-sentinel-client)
   :nemesis nemesis/noop
   :concurrency 15
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
                    reads (gen/repeat {:type :invoke :f :read})
                    writes (->> (gen/repeat {:type :invoke :f :write})
                                (gen/map (fn [op] (assoc op :value (swap! counter inc)))))
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

(defn intensive-test-concurrent []
  "Intensive test using concurrent generator pattern for better key distribution"
  {:name "redis-intensive-concurrent-test"
   :os debian/os
   :db (redis-db)
   :client (redis-sentinel-client)
   :nemesis nemesis/noop
   :concurrency 15
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

(defn split-brain-test []
  "Split-brain test with network partitions for 3 minutes"
  {:name "redis-split-brain-test"
   :os debian/os
   :db (redis-db)
   :client (redis-sentinel-client)
   :nemesis (nemesis/partition-random-halves)
   :net net/iptables
   :concurrency 15
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
                    reads (gen/repeat {:type :invoke :f :read})
                    writes (->> (gen/repeat {:type :invoke :f :write})
                                (gen/map (fn [op] (assoc op :value (swap! counter inc)))))
                    client-ops (->> [reads reads reads reads reads reads reads writes writes writes]
                                    (gen/mix)
                                    (gen/stagger 1/50))]
                (->> client-ops
                     (gen/nemesis
                      (cycle [(gen/sleep 10)
                              {:type :info, :f :start}
                              (gen/sleep 20)
                              {:type :info, :f :stop}]))
                     (gen/time-limit 180)))
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
  "Redis SET split-brain test with real Redis Sentinel"
  {:name "redis-set-split-brain-test"
   :os debian/os
   :db (redis-db)
   :client (redis-set-sentinel-client)
   :nemesis (nemesis/partition-random-halves)
   :net net/iptables
   :concurrency 8
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
                    reads-all (gen/repeat {:type :invoke :f :read-set-all})
                    client-ops (->> (concat (repeat 17 writes) (repeat 3 reads-all))
                                    (gen/mix)
                                    (gen/stagger 1/10))]
                (->> client-ops
                     (gen/nemesis
                      (cycle [(gen/sleep 15)
                              {:type :info, :f :start}
                              (gen/sleep 30)
                              {:type :info, :f :stop}
                              (gen/sleep 15)]))
                     (gen/time-limit 180)))
   :checker (checker/compose
             {:stats (checker/stats)
              :perf (checker/perf {:nemeses? true
                                   :bandwidth? true
                                   :quantiles [0.5 0.95 0.99]
                                   :subdirectory "perf-set-split-brain"})
              :timeline (timeline/html)
              :clock-plot (checker/clock-plot)
              :unhandled-exceptions (checker/unhandled-exceptions)
              :split-brain-analysis (reify checker/Checker
                                      (check [this test history opts]
                                        (let [writes (->> history
                                                          (filter #(and (= :ok (:type %))
                                                                        (= :write-set (:f %))))
                                                          (map :value)
                                                          (into #{}))
                                              read-alls (->> history
                                                             (filter #(and (= :ok (:type %))
                                                                           (= :read-set-all (:f %))))
                                                             (map :value))
                                              final-states (when (seq read-alls)
                                                             (last read-alls))
                                              all-survivors (when final-states
                                                              (->> final-states
                                                                   vals
                                                                   (remove #(= % :unreachable))
                                                                   (apply clojure.set/union)
                                                                   (into #{})))
                                              total-writes (count writes)
                                              survivors (count (or all-survivors #{}))
                                              lost-writes (clojure.set/difference writes (or all-survivors #{}))
                                              loss-rate (if (> total-writes 0)
                                                          (/ (count lost-writes) total-writes)
                                                          0)
                                              split-brain-detected? (when final-states
                                                                      (let [node-states (->> final-states
                                                                                             (remove #(= (val %) :unreachable))
                                                                                             (map val)
                                                                                             (map set)
                                                                                             distinct)]
                                                                        (> (count node-states) 1)))]
                                          {:valid? false
                                           :total-writes total-writes
                                           :survivors survivors
                                           :lost-writes (sort lost-writes)
                                           :loss-rate loss-rate
                                           :final-node-states final-states
                                           :split-brain-detected split-brain-detected?
                                           :message (str "📊 Split-brain test results:\n"
                                                         "   Total writes: " total-writes "\n"
                                                         "   Survivors: " survivors "\n"
                                                         "   Lost: " (count lost-writes) 
                                                         " (" (Math/round (* loss-rate 100)) "%)\n"
                                                         "   Split-brain detected: " split-brain-detected? "\n"
                                                         "   Final node states: " final-states)})))})})

(defn run-simple-test []
  (info "🚀 Starting simple Redis test for 30 seconds with Sentinel client...")
  (info "📊 Expected ~900 operations (3 threads × 10 ops/sec × 30 sec)")
  (jepsen/run! (simple-test)))

(defn run-intensive-test []
  (info "🚀 Starting intensive Redis test for 3 minutes with Sentinel client...")
  (info "📊 Expected ~135,000 operations (15 threads × 50 ops/sec × 180 sec)")
  (jepsen/run! (intensive-test)))

(defn run-intensive-concurrent-test []
  (info "🚀 Starting intensive concurrent Redis test for 3 minutes with Sentinel client...")
  (jepsen/run! (intensive-test-concurrent)))

(defn run-split-brain-test []
  (info "🚀 Starting split-brain Redis test for 3 minutes with Sentinel client...")
  (info "🧠 Network partitions: 10s normal → 20s partition → 10s normal → repeat")
  (info "📊 Expected ~135,000 operations with partition tolerance testing")
  (jepsen/run! (split-brain-test)))

(defn run-redis-set-split-brain-test []
  (info "🚀 Starting Redis SET split-brain test with Sentinel for 3 minutes...")
  (info "🧠 This test uses REAL Redis Sentinel for primary discovery")
  (info "📦 Writing incremental values to SET during network partitions")
  (info "📊 Reading from ALL nodes to show split-brain divergence")
  (info "⚠️  Expect significant data loss and split-brain detection!")
  (jepsen/run! (redis-set-split-brain-test)))

(defn -main [& args]
  (c/with-ssh {:username "root"
               :private-key-path "/root/.ssh/id_rsa"
               :strict-host-key-checking false
               :ssh-opts ["-o" "StrictHostKeyChecking=no"
                          "-o" "UserKnownHostsFile=/dev/null"
                          "-o" "GlobalKnownHostsFile=/dev/null"
                          "-o" "LogLevel=ERROR"]}
    (info "✅ Verifying SSH connectivity before starting test")
    (doseq [node ["n1" "n2" "n3" "n4" "n5"]]
      (try
        (c/on node
              (info "Uptime from" node ": " (c/exec :uptime)))
        (catch Exception e
          (error "Failed to connect to" node ":" (.getMessage e)))))
    (let [test-type (first args)]
      (case test-type
        "simple" (run-simple-test)
        "intensive" (run-intensive-test)
        "concurrent" (run-intensive-concurrent-test)
        "split-brain" (run-split-brain-test)
        "set-split-brain" (run-redis-set-split-brain-test)
        (do
          (info "🎯 Available tests (ALL use Sentinel clients):")
          (info "  simple - Basic register test with Sentinel")
          (info "  intensive - High-concurrency register test with Sentinel")
          (info "  concurrent - Concurrent register test with Sentinel")
          (info "  split-brain - Register split-brain test with Sentinel")
          (info "  set-split-brain - SET split-brain test with Sentinel")
          (info "🎯 Running simple test by default...")
          (run-simple-test))))))