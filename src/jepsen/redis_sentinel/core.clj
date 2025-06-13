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

(def fault-test-configurations
  {:network-partition-mild
   {:client-count 8
    :duration-ms 120000
    :workload-type :register
    :ops-per-second 15
    :fault-type :network-partition
    :fault-frequency 30
    :fault-duration 10000
    :workload-params {:register-count 10}}
   
   :process-crash-test
   {:client-count 10
    :duration-ms 150000
    :workload-type :mixed
    :ops-per-second 25
    :fault-type :process-crash
    :fault-frequency 25
    :fault-duration 8000
    :workload-params {:key-count 20}}
   
   :chaos-test
   {:client-count 15
    :duration-ms 300000
    :workload-type :register
    :ops-per-second 25
    :fault-type :chaos
    :fault-frequency 20
    :fault-duration 8000
    :workload-params {:register-count 25}}})

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


(defn run-fault-injection-suite
  "Run fault injection tests using existing infrastructure"
  []
  (log/info "=== Starting Fault Injection Test Suite ===")
  (doseq [[test-name config] fault-test-configurations]
    (log/info "=== Running fault test:" test-name "===")
    (try
      (runner/run-fault-injection-test config)
      (log/info "Fault test" test-name "completed successfully")
      (catch Exception e
        (log/error "Fault test" test-name "failed:" e)))
    (Thread/sleep 10000)))

(defn run-specific-fault-test
  "Run a specific fault test by name"
  [test-name]
  (if-let [config (get fault-test-configurations test-name)]
    (do
      (log/info "Running fault test:" test-name)
      (runner/run-fault-injection-test config))
    (log/error "Unknown fault test name:" test-name)))


(defn -main [& args]
  (cond
    (empty? args) (run-test-suite)
    (= (first args) "specialized") (run-specialized-tests)
    (= (first args) "faults") (run-fault-injection-suite)
    (= (first args) "chaos") (run-specific-fault-test :chaos-test)
    (contains? test-configurations (keyword (first args))) 
    (runner/run-linearizability-test (get test-configurations (keyword (first args))))
    (contains? fault-test-configurations (keyword (first args)))
    (run-specific-fault-test (keyword (first args)))
    :else (log/error "Unknown test:" (first args))))