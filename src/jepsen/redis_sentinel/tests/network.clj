(ns jepsen.redis-sentinel.tests.network
  (:require [clojure.tools.logging :refer :all]
            [jepsen.checker :as checker]
            [jepsen.control :as c]
            [jepsen.db :as db]
            [jepsen.net :as net]
            [jepsen.generator :as gen]
            [jepsen.core :as jepsen]
            [jepsen.nemesis :as nemesis]
            [jepsen.checker.timeline :as timeline]
            [jepsen.os.debian :as debian]
            [knossos.model :as model]
            [jepsen.redis-sentinel.client :as redis-client]))

(defn redis-db []
  (reify db/DB
    (setup! [_ test node]
      (info "Setting up Redis on" node)
      (c/exec :redis-cli :flushall))
    (teardown! [_ test node]
      (info "Cleaning up Redis on" node))))

(defn flapping-partitions-test []
  "Flapping partitions test - rapid partition/heal cycles to test stability under network instability for 3 minutes"
  {:name "redis-flapping-partitions-test"
   :os debian/os
   :db (redis-db)
   :client (redis-client/redis-sentinel-client)
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
                                          {:valid? (and (> partition-starts 10)  
                                                        (> success-rate 0.5))    
                                           :total-operations total-ops
                                           :successful-operations (+ (count writes) (count reads))
                                           :failed-operations failed-ops
                                           :timeout-operations timeout-ops
                                           :success-rate success-rate
                                           :partition-starts partition-starts
                                           :partition-stops partition-stops
                                           :primary-switches primary-switches
                                           :flapping-intensity (/ partition-starts 3.0)
                                           :message (str " Flapping Partitions Test Results:\n"
                                                         "   Total operations: " total-ops "\n"
                                                         "   Success rate: " (int (* (or success-rate 0) 100)) "%\n"
                                                         "   Partition starts: " partition-starts "\n"
                                                         "   Partition stops: " partition-stops "\n"
                                                         "   Primary switches: " primary-switches "\n"
                                                         "   Flapping intensity: " (int (/ partition-starts 3.0)) " partitions/min\n"
                                                         "   Network stability: " (if (> success-rate 0.7) "Good" "Poor"))})))})})

(defn bridge-partitions-test []
  "Bridge partitions test - creates chain-like connectivity patterns for 3 minutes"
  {:name "redis-bridge-partitions-test"
   :os debian/os
   :db (redis-db)
   :client (redis-client/redis-sentinel-client)
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
                                     {:valid? (and (> bridge-starts 2)    
                                                   (> success-rate 0.4))  
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
                                      :message (str " Bridge Partitions Test Results:\n"
                                                    "   Total operations: " total-ops "\n"
                                                    "   Success rate: " (int (* (or success-rate 0) 100)) "%\n"
                                                    "   Bridge events: " bridge-starts "\n"
                                                    "   Primary switches: " primary-switches "\n"
                                                    "   Node usage: " node-usage "\n"
                                                    "   Connectivity: " connectivity-health "\n"
                                                    "   Bridge effectiveness: " (int (* (/ failed-ops (max total-ops 1)) 100)) "% disruption")})))})})

;; Public run functions
(defn run-flapping-partitions-test []
  (info " Starting flapping partitions Redis test for 3 minutes with Sentinel client...")

  (jepsen/run! (flapping-partitions-test)))

(defn run-bridge-partitions-test []
  (info " Starting bridge partitions Redis test for 3 minutes with Sentinel client...")

  (jepsen/run! (bridge-partitions-test)))