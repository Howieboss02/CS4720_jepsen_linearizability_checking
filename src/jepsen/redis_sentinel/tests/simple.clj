(ns jepsen.redis-sentinel.tests.simple
  (:require [clojure.tools.logging :refer :all]
            [jepsen.checker :as checker]
            [jepsen.control :as c]
            [jepsen.db :as db] 
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

(defn simple-test []
  "Basic register test with Sentinel (30s, 3 threads)"
  {:name "redis-simple-test"
   :os debian/os
   :db (redis-db)
   :client (redis-client/redis-sentinel-client)
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
  "High-concurrency register test (3min, 15 threads)"
  {:name "redis-intensive-test"
   :os debian/os
   :db (redis-db)
   :client (redis-client/redis-sentinel-client)
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

(defn concurrent-test []
  "Concurrent register test with detailed analysis (3min)"
  {:name "redis-intensive-concurrent-test"
   :os debian/os
   :db (redis-db)
   :client (redis-client/redis-sentinel-client)
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

;; Public run functions
(defn run-simple-test []
  (info " Starting simple Redis test for 30 seconds with Sentinel client...")
  (jepsen/run! (simple-test)))

(defn run-intensive-test []
  (info " Starting intensive Redis test for 3 minutes with Sentinel client...")
  (jepsen/run! (intensive-test)))

(defn run-concurrent-test []
  (info " Starting intensive concurrent Redis test for 3 minutes with Sentinel client...")
  (jepsen/run! (concurrent-test)))