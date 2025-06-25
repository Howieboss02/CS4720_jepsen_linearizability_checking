(ns jepsen.redis-sentinel.tests.bank
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
              [taoensso.carmine :as car :refer [wcar]]
              [jepsen.redis-sentinel.client :as redis-client]))

(defn redis-db []
  (reify db/DB
         (setup! [_ test node]
                 (info "Setting up Redis on" node)
                 (c/exec :redis-cli :flushall))
         (teardown! [_ test node]
                    (info "Cleaning up Redis on" node))))

(defn bank-simple-test []
  {:name "bank-simple-test"
   :os debian/os
   :db (redis-db)
   :client (redis-client/bank-client)
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
                    reads (gen/repeat {:type :invoke :f :read-balance})
                    credits (->> (gen/repeat {:type :invoke :f :credit})
                                 (gen/map (fn [op] (assoc op :value (+ 10 (rand-int 20))))))
                    debits (->> (gen/repeat {:type :invoke :f :debit})
                                (gen/map (fn [op] (assoc op :value (+ 5 (rand-int 15))))))
                    client-ops (gen/mix [reads credits debits])]
                (->> client-ops
                     (gen/stagger 1/10)
                     (gen/nemesis
                      (cycle [(gen/sleep 5)
                              {:type :info, :f :start}
                              (gen/sleep 5)
                              {:type :info, :f :stop}]))
                     (gen/time-limit 30)))
   :checker (checker/compose
             {:perf (checker/concurrency-limit
                     2
                     (checker/perf {:nemeses? true
                                    :bandwidth? true
                                    :quantiles [0.25 0.5 0.75 0.9 0.95 0.99 0.999]
                                    :subdirectory "perf-bank-test"}))
              :latency-detailed (checker/concurrency-limit
                                 1
                                 (checker/latency-graph {:nemeses? true
                                                         :subdirectory "latency-bank-test"
                                                         :quantiles [0.1 0.25 0.5 0.75 0.9 0.95 0.99 0.999]}))
              :rate-detailed (checker/rate-graph {:nemeses? true
                                                  :subdirectory "rate-bank-test"
                                                  :quantiles [0.25 0.5 0.75 0.9 0.95 0.99]})
              :timeline (timeline/html)
              :clock-plot (checker/clock-plot)
              :unhandled-exceptions (checker/unhandled-exceptions)})})

(defn run-bank-simple-test []
  (info "🚀 Starting simple bank test for 30 seconds with Sentinel client...")
  (info "🏦 Testing account:1 with credit/debit operations")
  (info "📊 Expected ~900 operations (3 threads × 10 ops/sec × 30 sec)")
  (jepsen/run! (bank-simple-test)))