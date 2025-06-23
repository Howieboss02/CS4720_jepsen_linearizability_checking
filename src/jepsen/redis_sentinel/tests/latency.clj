(ns jepsen.redis-sentinel.tests.latency
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
   :client (redis-client/redis-sentinel-client)
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
   :generator
   (let [counter (atom 0)
         reads (gen/repeat {:type :invoke :f :read})
         writes (->> (gen/repeat {:type :invoke :f :write})
                     (gen/map (fn [op] (assoc op :value (swap! counter inc)))))
         opts {:rate 50}
         client-ops (gen/mix [reads writes])]
     (->> client-ops
          (gen/stagger (/ (:rate opts)))
          (gen/nemesis
           (cycle [(gen/sleep 15)
                   {:type :info, :f :start, :latency 300, :jitter 100}
                   (gen/sleep 25)
                   {:type :info, :f :stop}
                   (gen/sleep 15)
                   {:type :info, :f :start, :latency 500, :jitter 150}
                   (gen/sleep 20)
                   {:type :info, :f :stop}]))
          (gen/time-limit 180)))
   :checker
   (checker/compose
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
     :clock-analysis (checker/clock-plot)
     :exceptions (checker/unhandled-exceptions)
     :timeout-analysis
     (reify checker/Checker
       (check [this test history opts]
         (let [writes (->> history
                           (filter #(and (= :ok (:type %)) (= :write (:f %))))
                           (map (juxt :time :node :value)))
               reads (->> history
                          (filter #(and (= :ok (:type %)) (= :read (:f %))))
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
               all-ops (->> history
                            (filter #(#{:ok :fail} (:type %)))
                            (filter #(#{:read :write} (:f %)))
                            (map :latency)
                            (filter some?)
                            (filter number?))
               avg-latency (if (seq all-ops)
                             (/ (reduce + all-ops) (count all-ops))
                             0)]
           {:valid? (< timeout-rate 0.3)
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
                          "   Success rate: " (int (* (or success-rate 0) 100)) "%\n"
                          "   Timeout rate: " (int (* (or timeout-rate 0) 100)) "%\n"
                          "   Timeout operations: " timeout-ops "\n"
                          "   Latency periods: " latency-periods "\n"
                          "   Average latency: " (int (or avg-latency 0)) "ms\n"
                          "   Timeout threshold test: " (if (< timeout-rate 0.3) "PASSED" "FAILED"))})))})})


(defn extreme-latency-injection-test []
  "Extreme latency injection test - designed to break Redis Sentinel timeouts"
  {:name "redis-extreme-latency-injection-test"
   :os debian/os
   :db (redis-db)
   :client (redis-client/redis-sentinel-client)
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
                        {:type :info, :f :start, :latency 2000, :jitter 500}
                        (gen/sleep 15)
                        {:type :info, :f :stop}

                        ;; Phase 2: Break Sentinel detection
                        (gen/sleep 10)
                        {:type :info, :f :start, :latency 6000, :jitter 1000}
                        (gen/sleep 20)
                        {:type :info, :f :stop}

                        ;; Phase 3: Extreme latency - break everything
                        (gen/sleep 10)
                        {:type :info, :f :start, :latency 10000, :jitter 2000}
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
              :exceptions (checker/unhandled-exceptions)
              :breakage-analysis
              (reify checker/Checker
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
                        writes (->> history
                                    (filter #(and (= :ok (:type %))
                                                  (= :write (:f %))))
                                    (map :node)
                                    set)
                        failovers-detected (> (count writes) 1)
                        breakage-level (cond
                                         (> timeout-rate 0.8) "CATASTROPHIC"
                                         (> timeout-rate 0.5) "SEVERE"
                                         (> timeout-rate 0.2) "MODERATE"
                                         (> timeout-rate 0.05) "MILD"
                                         :else "MINIMAL")]
                    {:valid? false
                     :total-operations total-ops
                     :failed-operations failed-ops
                     :timeout-operations timeout-ops
                     :failure-rate failure-rate
                     :timeout-rate timeout-rate
                     :failovers-detected failovers-detected
                     :unique-primaries writes
                     :breakage-level breakage-level
                     :message (str "💥 EXTREME Latency Injection Results:\n"
                                   "   Breakage Level: " breakage-level "\n"
                                   "   Total operations: " total-ops "\n"
                                   "   Failed operations: " failed-ops " (" (int (* (or failure-rate 0) 100)) "%)\n"
                                   "   Timeout operations: " timeout-ops " (" (int (* (or timeout-rate 0) 100)) "%)\n"
                                   "   Failovers detected: " failovers-detected "\n"
                                   "   Primary nodes used: " (count writes))})))})})


;; Public run functions
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