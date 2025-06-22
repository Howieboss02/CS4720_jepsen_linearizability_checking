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
                                              ;; Count distinct primaries used for writes throughout the test
                                              primary-nodes (->> history
                                                                 (filter #(and (= :ok (:type %))
                                                                               (= :write-set (:f %))
                                                                               (:node %)))
                                                                 (map :node)
                                                                 set)
                                              num-distinct-primaries (count primary-nodes)
                                              ;; Find the test start time

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
                                                                        (> (count node-states) 1)))
                                              ;; Check if multiple primaries indicates true split-brain
                                              true-split-brain? (> num-distinct-primaries 1)]
                                          {:valid? false
                                           :total-writes total-writes
                                           :survivors survivors
                                           :lost-writes (sort lost-writes)
                                           :loss-rate loss-rate
                                           :final-node-states final-states
                                           :distinct-primaries num-distinct-primaries
                                           :primary-nodes (sort primary-nodes)
                                           :message (str "📊 Split-brain test results:\n"
                                                         "   Total writes: " total-writes "\n"
                                                         "   Survivors: " survivors "\n"
                                                         "   Lost: " (count lost-writes) 
                                                         " (" (int (* (or loss-rate 0) 100)) "%)\n"
                                                         "   Distinct primaries used: " num-distinct-primaries " " (sort primary-nodes) "\n"
                                                         "   Final node states: " final-states)})))})})

(defn isolated-primary-test []
  "Isolated primary test - isolates current primary to force immediate failover for 3 minutes"
  {:name "redis-isolated-primary-test"
   :os debian/os
   :db (redis-db)
   :client (redis-sentinel-client)
   :nemesis (nemesis/partitioner nemesis/majorities-ring-perfect)
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
                    opts {:rate 50}
                    client-ops (gen/mix [reads writes])]
                (->> client-ops
                     (gen/stagger (/ (:rate opts)))
                     (gen/nemesis
                      (cycle [(gen/sleep 10)
                              {:type :info, :f :start}
                              (gen/sleep 30)
                              {:type :info, :f :stop}
                              (gen/sleep 10)]))
                     (gen/time-limit 180)))
   :checker (checker/compose
             {:stats (checker/stats)
              :perf (checker/concurrency-limit
                     2
                     (checker/perf {:nemeses? true
                                    :bandwidth? true
                                    :quantiles [0.25 0.5 0.75 0.9 0.95 0.99 0.999]
                                    :subdirectory "perf-isolated-primary"}))
              :latency-detailed (checker/concurrency-limit
                                 1
                                 (checker/latency-graph {:nemeses? true
                                                         :subdirectory "latency-isolated-primary"
                                                         :quantiles [0.1 0.25 0.5 0.75 0.9 0.95 0.99 0.999]}))
              :rate-detailed (checker/rate-graph {:nemeses? true
                                                  :subdirectory "rate-isolated-primary"
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
              :failover-analysis (reify checker/Checker
                                   (check [this test history opts]
                                     (let [writes (->> history
                                                       (filter #(and (= :ok (:type %))
                                                                     (= :write (:f %))))
                                                       (map (juxt :time :node :value)))
                                           nemesis-events (->> history
                                                               (filter #(= :nemesis (:process %)))
                                                               (map (juxt :time :f)))
                                           primary-changes (->> writes
                                                                (partition-by second)
                                                                (map (fn [group] 
                                                                       {:time (ffirst group)
                                                                        :primary (-> group first second)
                                                                        :count (count group)})))
                                           total-writes (count writes)
                                           unique-primaries (set (map second writes))
                                           failover-count (dec (count primary-changes))]
                                       {:valid? (>= failover-count 1)
                                        :total-writes total-writes
                                        :unique-primaries unique-primaries
                                        :failover-count failover-count
                                        :primary-changes primary-changes
                                        :nemesis-events nemesis-events
                                        :message (str "🔄 Isolated Primary Test Results:\n"
                                                      "   Total writes: " total-writes "\n"
                                                      "   Unique primaries used: " (count unique-primaries) "\n"
                                                      "   Detected failovers: " failover-count "\n"
                                                      "   Primary nodes: " unique-primaries "\n"
                                                      "   Failover successful: " (>= failover-count 1))})))})})

(defn flapping-partitions-test []
  "Flapping partitions test - rapid partition/heal cycles to test stability under network instability for 3 minutes"
  {:name "redis-flapping-partitions-test"
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
                    opts {:rate 50}
                    client-ops (gen/mix [reads writes])]
                (->> client-ops
                     (gen/stagger (/ (:rate opts)))
                     (gen/nemesis
                      ;; Rapid flapping: 3s normal → 2s partition → 3s heal → 2s partition → repeat
                      (cycle [(gen/sleep 3)
                              {:type :info, :f :start}
                              (gen/sleep 2)
                              {:type :info, :f :stop}
                              (gen/sleep 3)
                              {:type :info, :f :start}
                              (gen/sleep 2)
                              {:type :info, :f :stop}]))
                     (gen/time-limit 180)))
   :checker (checker/compose
             {:stats (checker/stats)
              :perf (checker/concurrency-limit
                     2
                     (checker/perf {:nemeses? true
                                    :bandwidth? true
                                    :quantiles [0.25 0.5 0.75 0.9 0.95 0.99 0.999]
                                    :subdirectory "perf-flapping-partitions"}))
              :latency-detailed (checker/concurrency-limit
                                 1
                                 (checker/latency-graph {:nemeses? true
                                                         :subdirectory "latency-flapping-partitions"
                                                         :quantiles [0.1 0.25 0.5 0.75 0.9 0.95 0.99 0.999]}))
              :rate-detailed (checker/rate-graph {:nemeses? true
                                                  :subdirectory "rate-flapping-partitions"
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
              :instability-analysis (reify checker/Checker
                                      (check [this test history opts]
                                        (let [writes (->> history
                                                          (filter #(and (= :ok (:type %))
                                                                        (= :write (:f %))))
                                                          (map (juxt :time :node :value)))
                                              reads (->> history
                                                         (filter #(and (= :ok (:type %))
                                                                       (= :read (:f %))))
                                                         (map (juxt :time :node :value)))
                                              nemesis-events (->> history
                                                                  (filter #(= :nemesis (:process %)))
                                                                  (map (juxt :time :f)))
                                              partition-starts (count (filter #(= :start (second %)) nemesis-events))
                                              partition-stops (count (filter #(= :stop (second %)) nemesis-events))
                                              failed-ops (->> history
                                                              (filter #(= :fail (:type %)))
                                                              count)
                                              timeout-ops (->> history
                                                               (filter #(and (= :info (:type %))
                                                                             (= :timeout (:f %))))
                                                               count)
                                              total-ops (+ (count writes) (count reads) failed-ops timeout-ops)
                                              success-rate (if (> total-ops 0)
                                                             (/ (+ (count writes) (count reads)) total-ops)
                                                             0)
                                              primary-switches (->> writes
                                                                    (partition-by second)
                                                                    count
                                                                    dec)]
                                          {:valid? (and (> partition-starts 10)  ; Expect many rapid partitions
                                                        (> success-rate 0.5))    ; At least 50% success rate
                                           :total-operations total-ops
                                           :successful-operations (+ (count writes) (count reads))
                                           :failed-operations failed-ops
                                           :timeout-operations timeout-ops
                                           :success-rate success-rate
                                           :partition-starts partition-starts
                                           :partition-stops partition-stops
                                           :primary-switches primary-switches
                                           :flapping-intensity (/ partition-starts 3.0) ; partitions per minute
                                           :message (str "🌊 Flapping Partitions Test Results:\n"
                                                         "   Total operations: " total-ops "\n"
                                                         "   Success rate: " (int (* (or success-rate 0) 100)) "%\n"  ; FIXED: Use int instead of Math/round
                                                         "   Partition starts: " partition-starts "\n"
                                                         "   Partition stops: " partition-stops "\n"
                                                         "   Primary switches: " primary-switches "\n"
                                                         "   Flapping intensity: " (int (/ partition-starts 3.0)) " partitions/min\n"  ; FIXED: Use int instead of Math/round
                                                         "   Network stability: " (if (> success-rate 0.7) "Good" "Poor"))})))})})

(defn bridge-partitions-test []
  "Bridge partitions test - creates chain-like connectivity patterns for 3 minutes"
  {:name "redis-bridge-partitions-test"
   :os debian/os
   :db (redis-db)
   :client (redis-sentinel-client)
   :nemesis (nemesis/partitioner nemesis/bridge)
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
                    opts {:rate 50}
                    client-ops (gen/mix [reads writes])]
                (->> client-ops
                     (gen/stagger (/ (:rate opts)))
                     (gen/nemesis
                      ;; Bridge partition pattern: 12s normal → 18s bridge → 12s heal
                      (cycle [(gen/sleep 12)
                              {:type :info, :f :start}
                              (gen/sleep 18)
                              {:type :info, :f :stop}]))
                     (gen/time-limit 180)))
   :checker (checker/compose
             {:stats (checker/stats)
              :perf (checker/concurrency-limit
                     2
                     (checker/perf {:nemeses? true
                                    :bandwidth? true
                                    :quantiles [0.25 0.5 0.75 0.9 0.95 0.99 0.999]
                                    :subdirectory "perf-bridge-partitions"}))
              :latency-detailed (checker/concurrency-limit
                                 1
                                 (checker/latency-graph {:nemeses? true
                                                         :subdirectory "latency-bridge-partitions"
                                                         :quantiles [0.1 0.25 0.5 0.75 0.9 0.95 0.99 0.999]}))
              :rate-detailed (checker/rate-graph {:nemeses? true
                                                  :subdirectory "rate-bridge-partitions"
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
              :bridge-analysis (reify checker/Checker
                                 (check [this test history opts]
                                   (let [writes (->> history
                                                     (filter #(and (= :ok (:type %))
                                                                   (= :write (:f %))))
                                                     (map (juxt :time :node :value)))
                                         reads (->> history
                                                    (filter #(and (= :ok (:type %))
                                                                  (= :read (:f %))))
                                                    (map (juxt :time :node :value)))
                                         nemesis-events (->> history
                                                             (filter #(= :nemesis (:process %)))
                                                             (map (juxt :time :f)))
                                         bridge-starts (count (filter #(= :start (second %)) nemesis-events))
                                         bridge-stops (count (filter #(= :stop (second %)) nemesis-events))
                                         failed-ops (->> history
                                                         (filter #(= :fail (:type %)))
                                                         count)
                                         total-ops (+ (count writes) (count reads) failed-ops)
                                         success-rate (if (> total-ops 0)
                                                        (/ (+ (count writes) (count reads)) total-ops)
                                                        0)
                                         primary-switches (->> writes
                                                               (partition-by second)
                                                               count
                                                               dec)
                                         ;; Analyze connectivity patterns during bridge partitions
                                         node-usage (->> writes
                                                         (group-by second)
                                                         (map (fn [[node ops]] [node (count ops)]))
                                                         (into {}))
                                         connectivity-health (if (> (count node-usage) 1)
                                                               "Bridge connectivity maintained"
                                                               "Limited connectivity detected")]
                                     {:valid? (and (> bridge-starts 2)    ; Expect multiple bridge events
                                                   (> success-rate 0.4))   ; At least 40% success (bridge is restrictive)
                                      :total-operations total-ops
                                      :successful-operations (+ (count writes) (count reads))
                                      :failed-operations failed-ops
                                      :success-rate success-rate
                                      :bridge-starts bridge-starts
                                      :bridge-stops bridge-stops
                                      :primary-switches primary-switches
                                      :node-usage node-usage
                                      :connectivity-health connectivity-health
                                      :bridge-effectiveness (/ failed-ops (max total-ops 1))
                                      :message (str "🌉 Bridge Partitions Test Results:\n"
                                                    "   Total operations: " total-ops "\n"
                                                    "   Success rate: " (int (* (or success-rate 0) 100)) "%\n"  ; FIXED: Use int instead of Math/round
                                                    "   Bridge events: " bridge-starts "\n"
                                                    "   Primary switches: " primary-switches "\n"
                                                    "   Node usage: " node-usage "\n"
                                                    "   Connectivity: " connectivity-health "\n"
                                                    "   Bridge effectiveness: " (int (* (/ failed-ops (max total-ops 1)) 100)) "% disruption")})))})})

(defn latency-nemesis
  "Custom nemesis that injects network latency using Linux tc (traffic control)"
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)

    (invoke! [this test op]
      (case (:f op)
        :start
        (let [nodes (:nodes test)
              latency-ms (or (:latency op) 200)  ; Default 200ms latency
              jitter-ms (or (:jitter op) 50)]    ; Default 50ms jitter
          (info "Injecting" latency-ms "ms latency with" jitter-ms "ms jitter on all nodes")
          (c/on-nodes test nodes
                      (fn [test node]
                        (c/su
                         ;; Clear any existing tc rules
                         (c/exec :tc :qdisc :del :dev :eth0 :root :|| :true)
                         ;; Add latency to outgoing packets
                         (c/exec :tc :qdisc :add :dev :eth0 :root :handle "1:"
                                 :netem :delay (str latency-ms "ms") (str jitter-ms "ms")))))
          (assoc op :type :info :value {:latency latency-ms :jitter jitter-ms :affected nodes}))

        :stop
        (let [nodes (:nodes test)]
          (info "Removing latency injection from all nodes")
          (c/on-nodes test nodes
                      (fn [test node]
                        (c/su
                         ;; Remove tc rules to restore normal latency
                         (c/exec :tc :qdisc :del :dev :eth0 :root :|| :true))))
          (assoc op :type :info :value :latency-removed))

        ;; Default case for unknown operations
        (assoc op :type :info :error (str "Unknown latency nemesis operation: " (:f op)))))

    (teardown! [this test]
      ;; Ensure cleanup on teardown
      (let [nodes (:nodes test)]
        (doseq [node nodes]
          (try
            (c/on node
                  (c/su (c/exec :tc :qdisc :del :dev :eth0 :root :|| :true)))
            (catch Exception e
              (warn "Failed to cleanup tc rules on" node ":" (.getMessage e)))))))))


(defn latency-injection-test []
  "Latency injection test - adds network delays to test timeout thresholds for 3 minutes"
  {:name "redis-latency-injection-test"
   :os debian/os
   :db (redis-db)
   :client (redis-sentinel-client)
   :nemesis (latency-nemesis)
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
                    opts {:rate 50}
                    client-ops (gen/mix [reads writes])]
                (->> client-ops
                     (gen/stagger (/ (:rate opts)))
                     (gen/nemesis
                      ;; Latency injection pattern: 15s normal → 25s high latency → 15s normal
                      (cycle [(gen/sleep 15)
                              {:type :info, :f :start, :latency 300, :jitter 100}  ; 300ms ± 100ms
                              (gen/sleep 25)
                              {:type :info, :f :stop}
                              (gen/sleep 15)
                              {:type :info, :f :start, :latency 500, :jitter 150}  ; 500ms ± 150ms  
                              (gen/sleep 20)
                              {:type :info, :f :stop}]))
                     (gen/time-limit 180)))
   :checker (checker/compose
             {:stats (checker/stats)
              :perf (checker/concurrency-limit
                     2
                     (checker/perf {:nemeses? true
                                    :bandwidth? true
                                    :quantiles [0.25 0.5 0.75 0.9 0.95 0.99 0.999]
                                    :subdirectory "perf-latency-injection"}))
              :latency-detailed (checker/concurrency-limit
                                 1
                                 (checker/latency-graph {:nemeses? true
                                                         :subdirectory "latency-injection"
                                                         :quantiles [0.1 0.25 0.5 0.75 0.9 0.95 0.99 0.999]}))
              :rate-detailed (checker/rate-graph {:nemeses? true
                                                  :subdirectory "rate-latency-injection"
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
              :timeout-analysis (reify checker/Checker
                                  (check [this test history opts]
                                    (let [writes (->> history
                                                      (filter #(and (= :ok (:type %))
                                                                    (= :write (:f %))))
                                                      (map (juxt :time :node :value)))
                                          reads (->> history
                                                     (filter #(and (= :ok (:type %))
                                                                   (= :read (:f %))))
                                                     (map (juxt :time :node :value)))
                                          failed-ops (->> history
                                                          (filter #(= :fail (:type %)))
                                                          count)
                                          timeout-ops (->> history
                                                           (filter #(and (= :fail (:type %))
                                                                         (or (re-find #"timeout" (str (:error %)))
                                                                             (re-find #"Timeout" (str (:error %))))))
                                                           count)
                                          nemesis-events (->> history
                                                              (filter #(= :nemesis (:process %)))
                                                              (filter #(= :start (:f %)))
                                                              (map :value))
                                          latency-periods (count nemesis-events)
                                          total-ops (+ (count writes) (count reads) failed-ops)
                                          success-rate (if (> total-ops 0)
                                                         (/ (+ (count writes) (count reads)) total-ops)
                                                         0)
                                          timeout-rate (if (> total-ops 0)
                                                         (/ timeout-ops total-ops)
                                                         0)
                                          ;; Analyze operation latencies during high latency periods
                                          all-ops (->> history
                                                       (filter #(#{:ok :fail} (:type %)))
                                                       (filter #(#{:read :write} (:f %)))
                                                       (map (juxt :time :latency)))
                                          avg-latency (if (seq all-ops)
                                                        (/ (reduce + (map second all-ops)) (count all-ops))
                                                        0)]
                                      {:valid? (< timeout-rate 0.3)  ; Valid if timeout rate < 30%
                                       :total-operations total-ops
                                       :successful-operations (+ (count writes) (count reads))
                                       :failed-operations failed-ops
                                       :timeout-operations timeout-ops
                                       :success-rate success-rate
                                       :timeout-rate timeout-rate
                                       :latency-periods latency-periods
                                       :average-latency-ms avg-latency
                                       :latency-configurations nemesis-events
                                       :message (str "⏱️ Latency Injection Test Results:\n"
                                                     "   Total operations: " total-ops "\n"
                                                     "   Success rate: " (int (* (or success-rate 0) 100)) "%\n"  ; FIXED: Use int instead of Math/round
                                                     "   Timeout rate: " (int (* (or timeout-rate 0) 100)) "%\n"  ; FIXED: Use int instead of Math/round
                                                     "   Timeout operations: " timeout-ops "\n"
                                                     "   Latency periods: " latency-periods "\n"
                                                     "   Average latency: " (int (or avg-latency 0)) "ms\n"  ; FIXED: Use int instead of Math/round
                                                     "   Timeout threshold test: " (if (< timeout-rate 0.3) "PASSED" "FAILED"))})))})})

(defn extreme-latency-injection-test []
  "Extreme latency injection test - designed to break Redis Sentinel timeouts"
  {:name "redis-extreme-latency-injection-test"
   :os debian/os
   :db (redis-db)
   :client (redis-sentinel-client)
   :nemesis (latency-nemesis)
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
                    opts {:rate 50}
                    client-ops (gen/mix [reads writes])]
                (->> client-ops
                     (gen/stagger (/ (:rate opts)))
                     (gen/nemesis
                      ;; Progressive breakage pattern
                      (cycle [
                        ;; Phase 1: Break client timeouts
                        (gen/sleep 10)
                        {:type :info, :f :start, :latency 2000, :jitter 500}   ; 2s ± 0.5s
                        (gen/sleep 15)
                        {:type :info, :f :stop}
                        
                        ;; Phase 2: Break Sentinel detection
                        (gen/sleep 10)
                        {:type :info, :f :start, :latency 6000, :jitter 1000}  ; 6s ± 1s
                        (gen/sleep 20)
                        {:type :info, :f :stop}
                        
                        ;; Phase 3: Extreme latency - break everything
                        (gen/sleep 10)
                        {:type :info, :f :start, :latency 10000, :jitter 2000} ; 10s ± 2s
                        (gen/sleep 25)
                        {:type :info, :f :stop}]))
                     (gen/time-limit 180)))
   :checker (checker/compose
             {:stats (checker/stats)
              :perf (checker/concurrency-limit
                     2
                     (checker/perf {:nemeses? true
                                    :bandwidth? true
                                    :quantiles [0.25 0.5 0.75 0.9 0.95 0.99 0.999]
                                    :subdirectory "perf-extreme-latency"}))
              :latency-detailed (checker/concurrency-limit
                                 1
                                 (checker/latency-graph {:nemeses? true
                                                         :subdirectory "extreme-latency"
                                                         :quantiles [0.1 0.25 0.5 0.75 0.9 0.95 0.99 0.999]}))
              :timeline (timeline/html)
              :linear-wgl (checker/concurrency-limit
                           1
                           (checker/linearizable {:model (model/register)
                                                  :algorithm :wgl}))
              :exceptions (checker/unhandled-exceptions)
              :breakage-analysis (reify checker/Checker
                                   (check [this test history opts]
                                     (let [failed-ops (->> history
                                                           (filter #(= :fail (:type %)))
                                                           count)
                                           timeout-ops (->> history
                                                            (filter #(and (= :fail (:type %))
                                                                          (or (re-find #"timeout" (str (:error %)))
                                                                              (re-find #"Timeout" (str (:error %)))
                                                                              (re-find #"Connection" (str (:error %))))))
                                                            count)
                                           total-ops (->> history
                                                          (filter #(#{:ok :fail} (:type %)))
                                                          count)
                                           failure-rate (if (> total-ops 0)
                                                          (/ failed-ops total-ops)
                                                          0)
                                           timeout-rate (if (> total-ops 0)
                                                          (/ timeout-ops total-ops)
                                                          0)
                                           ;; Check for failover indicators
                                           writes (->> history
                                                       (filter #(and (= :ok (:type %))
                                                                     (= :write (:f %))))
                                                       (map :node))
                                           unique-primaries (set writes)
                                           failovers-detected (> (count unique-primaries) 1)
                                           
                                           ;; Categorize breakage severity
                                           breakage-level (cond
                                                            (> timeout-rate 0.8) "CATASTROPHIC"
                                                            (> timeout-rate 0.5) "SEVERE"
                                                            (> timeout-rate 0.2) "MODERATE"
                                                            (> timeout-rate 0.05) "MILD"
                                                            :else "MINIMAL")]
                                       
                                       {:valid? false  ; Expect breakage, so never "valid"
                                        :total-operations total-ops
                                        :failed-operations failed-ops
                                        :timeout-operations timeout-ops
                                        :failure-rate failure-rate
                                        :timeout-rate timeout-rate
                                        :failovers-detected failovers-detected
                                        :unique-primaries unique-primaries
                                        :breakage-level breakage-level
                                        :systems-broken (cond-> []
                                                          (> timeout-rate 0.1) (conj "Client timeouts")
                                                          failovers-detected (conj "Sentinel failover triggered")
                                                          (> failure-rate 0.5) (conj "System availability"))
                                        :message (str "💥 EXTREME Latency Injection Results:\n"
                                                      "   Breakage Level: " breakage-level "\n"
                                                      "   Total operations: " total-ops "\n"
                                                      "   Failed operations: " failed-ops " (" (int (* (or failure-rate 0) 100)) "%)\n"  ; FIXED: Use int instead of Math/round
                                                      "   Timeout operations: " timeout-ops " (" (int (* (or timeout-rate 0) 100)) "%)\n"  ; FIXED: Use int instead of Math/round
                                                      "   Failovers detected: " failovers-detected "\n"
                                                      "   Primary nodes used: " unique-primaries "\n"
                                                      "   Systems broken: " (or (seq (cond-> []
                                                                                            (> timeout-rate 0.1) (conj "Client timeouts")
                                                                                            failovers-detected (conj "Sentinel failover")
                                                                                            (> failure-rate 0.5) (conj "System availability")))
                                                                                 ["None"]))})))})})

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

(defn run-isolated-primary-test []
  (info "🚀 Starting isolated primary Redis test for 3 minutes with Sentinel client...")
  (info "🎯 This test isolates the current primary to force immediate failover")
  (info "🔄 Pattern: 10s normal → 15s isolation → 10s heal → repeat")
  (info "📊 Expected failover detection and primary switching")
  (jepsen/run! (isolated-primary-test)))

(defn run-flapping-partitions-test []
  (info "🚀 Starting flapping partitions Redis test for 3 minutes with Sentinel client...")
  (info "🌊 This test uses rapid partition/heal cycles to test network instability")
  (info "⚡ Pattern: 3s normal → 2s partition → 3s heal → 2s partition → repeat")
  (info "📊 Expected high partition frequency and stability testing")
  (info "🎯 Measuring system resilience under constant network disruption")
  (jepsen/run! (flapping-partitions-test)))

(defn run-bridge-partitions-test []
  (info "🚀 Starting bridge partitions Redis test for 3 minutes with Sentinel client...")
  (info "🌉 This test creates chain-like connectivity patterns (bridge partitions)")
  (info "🔗 Pattern: 12s normal → 18s bridge → 12s heal → repeat")
  (info "📊 Expected limited connectivity and consensus challenges")
  (info "🎯 Testing Sentinel behavior with partial network connectivity")
  (jepsen/run! (bridge-partitions-test)))

(defn run-latency-injection-test []
  (info "🚀 Starting latency injection Redis test for 3 minutes with Sentinel client...")
  (info "⏱️ This test injects network latency to test timeout thresholds")
  (info "🐌 Pattern: 15s normal → 25s (300ms±100ms) → 15s normal → 20s (500ms±150ms) → repeat")
  (info "📊 Expected timeout behavior and latency impact on operations")
  (info "🎯 Testing Sentinel and Redis client resilience to network delays")
  (jepsen/run! (latency-injection-test)))

(defn run-extreme-latency-injection-test []
  (info "🚀 Starting EXTREME latency injection Redis test for 3 minutes...")
  (info "💥 This test is designed to BREAK Redis Sentinel timeouts")
  (info "🐌 Pattern: 10s → 2s latency → 10s → 6s latency → 10s → 10s latency")
  (info "⚠️  EXPECT: Client timeouts, Sentinel failovers, system breakage!")
  (info "🎯 Testing maximum latency tolerance thresholds")
  (jepsen/run! (extreme-latency-injection-test)))

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
        "isolated-primary" (run-isolated-primary-test)
        "flapping-partitions" (run-flapping-partitions-test)
        "bridge-partitions" (run-bridge-partitions-test)
        "latency-injection" (run-latency-injection-test)
        "extreme-latency" (run-extreme-latency-injection-test)
        (do
          (info "🎯 Available tests (ALL use Sentinel clients):")
          (info "  simple - Basic register test with Sentinel")
          (info "  intensive - High-concurrency register test with Sentinel")
          (info "  concurrent - Concurrent register test with Sentinel")
          (info "  split-brain - Register split-brain test with Sentinel")
          (info "  set-split-brain - SET split-brain test with Sentinel")
          (info "  isolated-primary - Isolated primary failover test with Sentinel")
          (info "  flapping-partitions - Rapid partition/heal cycles test with Sentinel")
          (info "  bridge-partitions - Chain-like connectivity patterns test with Sentinel")
          (info "  latency-injection - Network latency injection test with Sentinel")
          (info "  extreme-latency - Extreme latency injection test with Sentinel")
          (info "🎯 Running simple test by default...")
          (run-simple-test))))))