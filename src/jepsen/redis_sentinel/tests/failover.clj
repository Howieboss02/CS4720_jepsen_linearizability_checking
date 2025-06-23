(ns jepsen.redis-sentinel.tests.failover
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

(defn majorities-ring-test []
  "Majorities ring test - uses ring partitions to force failover scenarios for 3 minutes"
  {:name "redis-majorities-ring-test"
   :os debian/os
   :db (redis-db)
   :client (redis-client/redis-sentinel-client)
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
                                    :subdirectory "perf-majorities-ring"}))
              :latency-detailed (checker/concurrency-limit
                                 1
                                 (checker/latency-graph {:nemeses? true
                                                         :subdirectory "latency-majorities-ring"
                                                         :quantiles [0.1 0.25 0.5 0.75 0.9 0.95 0.99 0.999]}))
              :rate-detailed (checker/rate-graph {:nemeses? true
                                                  :subdirectory "rate-majorities-ring"
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
                                        :message (str "🔄 Majorities Ring Test Results:\n"
                                                      "   Total writes: " total-writes "\n"
                                                      "   Unique primaries used: " (count unique-primaries) "\n"
                                                      "   Detected failovers: " failover-count "\n"
                                                      "   Primary nodes: " unique-primaries "\n"
                                                      "   Failover successful: " (>= failover-count 1))})))})})

;; Public run functions
(defn run-majorities-ring-test []
  (info "🚀 Starting majorities ring Redis test for 3 minutes with Sentinel client...")
  (info "🎯 This test uses ring partitions to force failover scenarios")
  (info "🔄 Pattern: 10s normal → 30s partition → 10s heal → repeat")
  (info "📊 Expected failover detection and primary switching")
  (jepsen/run! (majorities-ring-test)))