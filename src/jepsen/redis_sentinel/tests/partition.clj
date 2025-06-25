(ns jepsen.redis-sentinel.tests.partition
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

(defn split-brain-test []
  "Register split-brain test with network partitions (3min)"
  {:name "redis-split-brain-test"
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

(defn set-split-brain-test []
  "SET split-brain test with Sentinel discovery (3min)"
  {:name "redis-set-split-brain-test"
   :os debian/os
   :db (redis-db)
   :client (redis-client/redis-set-sentinel-client)
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
                                              primary-nodes (->> history
                                                                 (filter #(and (= :ok (:type %))
                                                                               (= :write-set (:f %))
                                                                               (:node %)))
                                                                 (map :node)
                                                                 set)
                                              num-distinct-primaries (count primary-nodes)
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
                                                          0)]
                                          {:valid? false
                                           :total-writes total-writes
                                           :survivors survivors
                                           :lost-writes (sort lost-writes)
                                           :loss-rate loss-rate
                                           :final-node-states final-states
                                           :distinct-primaries num-distinct-primaries
                                           :primary-nodes (sort primary-nodes)
                                           :message (str " Split-brain test results:\n"
                                                         "   Total writes: " total-writes "\n"
                                                         "   Survivors: " survivors "\n"
                                                         "   Lost: " (count lost-writes) 
                                                         " (" (int (* (or loss-rate 0) 100)) "%)\n"
                                                         "   Distinct primaries used: " num-distinct-primaries " " (sort primary-nodes) "\n"
                                                         "   Final node states: " final-states)})))})})

;; Public run functions
(defn run-split-brain-test []
  (info " Starting split-brain Redis test for 3 minutes with Sentinel client...")

  (jepsen/run! (split-brain-test)))

(defn run-set-split-brain-test []
  (info " Starting Redis SET split-brain test with Sentinel for 3 minutes...")

  (jepsen/run! (set-split-brain-test)))