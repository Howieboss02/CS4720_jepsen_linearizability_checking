(ns jepsen.redis-sentinel.core
  (:require [clojure.tools.logging :as log]
            [jepsen.redis-sentinel.runner :as runner]
            [jepsen.redis-sentinel.test-harness :as harness])
  (:gen-class))

(def test-configurations
  {:basic-linearizability
   {:client-count 5
    :duration-ms 30000
    :workload-type :register
    :ops-per-second 10
    :workload-params {:register-count 10}}
   
   :high-contention
   {:client-count 10
    :duration-ms 60000
    :workload-type :counter
    :ops-per-second 50
    :workload-params {:counter-count 5}}
   
   :all-replicas-stress
   {:client-count 20
    :duration-ms 60000
    :workload-type :mixed
    :ops-per-second 30
    :workload-params {:key-count 50}}
   
   :read-heavy-workload
   {:client-count 16
    :duration-ms 45000
    :workload-type :read-only
    :ops-per-second 40
    :workload-params {:key-count 30}}
   
   :write-heavy-workload
   {:client-count 8
    :duration-ms 45000
    :workload-type :write-only
    :ops-per-second 25
    :workload-params {:key-count 20}}
   
   :high-load-mixed
   {:client-count 25
    :duration-ms 90000
    :workload-type :mixed
    :ops-per-second 40
    :workload-params {:key-count 100 :register-count 30 :counter-count 15}}})

(defn run-test-suite []
  (doseq [[test-name config] test-configurations]
    (log/info "=== Running test:" test-name "===")
    (try
      (runner/run-linearizability-test config)
      (log/info "Test" test-name "completed successfully")
      (catch Exception e
        (log/error "Test" test-name "failed:" e)))
    (Thread/sleep 5000)))

(defn run-specialized-tests []
  "Run tests specifically designed for smart client testing"
  (log/info "=== Running Smart Client Specialized Tests ===")
  
  ;; Test 1: Read/Write Separation
  (log/info "Running read/write separated test...")
  (runner/run-read-write-separated-test 
    {:client-count 15 :duration-ms 45000 :ops-per-second 20 
     :workload-params {:key-count 30}})
  
  (Thread/sleep 3000)
  
  ;; Test 2: Replica Consistency
  (log/info "Running replica consistency test...")
  (runner/run-replica-consistency-test 
    {:duration-ms 60000 :ops-per-second 15})
  
  (Thread/sleep 3000)
  
  ;; Test 3: High Load Test
  (log/info "Running high load test...")
  (runner/run-high-load-test
    {:client-count 30 :duration-ms 90000 :ops-per-second 35
     :workload-params {:key-count 100 :register-count 40 :counter-count 20}})
  
  (log/info "All specialized tests completed!"))

(defn -main [& args]
  (cond
    (empty? args) (run-test-suite)
    (= (first args) "specialized") (run-specialized-tests)
    :else (runner/run-linearizability-test (get test-configurations (keyword (first args))))))