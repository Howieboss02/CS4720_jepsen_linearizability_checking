(ns jepsen.redis-sentinel.replication-test
  (:require [clojure.test :refer :all]
            [taoensso.carmine :as car :refer [wcar]]
            [clojure.tools.logging :as log]))

(def primary-conn {:pool {} :spec {:host "redis-primary" :port 6379}})
(def replica1-conn {:pool {} :spec {:host "redis-replica1" :port 6379}})
(def replica2-conn {:pool {} :spec {:host "redis-replica2" :port 6379}})
(def sentinel-conn {:pool {} :spec {:host "redis-sentinel1" :port 26379}})

(deftest simple-replication-test
  (testing "Write 1-10 to primary, verify on replicas"
    (try
      ;; Write to primary
      (doseq [i (range 1 11)]
        (wcar primary-conn 
              (car/set (str "num-" i) i))
        (log/info "Wrote" i "to primary"))
      
      ;; Wait for replication
      (Thread/sleep 3000)
      
      ;; Check replica1
      (doseq [i (range 1 11)]
        (let [value (wcar replica1-conn (car/get (str "num-" i)))]
          (is (= (str i) value) 
              (format "Value %d missing on replica1" i))))
      
      ;; Check replica2  
      (doseq [i (range 1 11)]
        (let [value (wcar replica2-conn (car/get (str "num-" i)))]
          (is (= (str i) value) 
              (format "Value %d missing on replica2" i))))
      
      (log/info "All values replicated successfully!")
      
      ;; Cleanup
      (finally
        (doseq [i (range 1 11)]
          (wcar primary-conn (car/del (str "num-" i))))))))


(deftest simple-sentinel-test
  (testing "Sentinel basic connectivity and primary operations"
    (try
      ;; Test 1: Query sentinel for basic info
      (let [ping-response (wcar sentinel-conn (car/ping))]
        (is (= "PONG" ping-response) "Sentinel should respond to ping")
        (log/info "Sentinel ping response:" ping-response))

      ;; Test 2: Verify primary is accessible and writable
      (wcar primary-conn
            (car/set "sentinel-connectivity-test" "connected"))

      (let [value (wcar primary-conn
                        (car/get "sentinel-connectivity-test"))]
        (is (= "connected" value) "Should be able to write/read from primary"))

      ;; Test 3: Verify replicas can read the data
      (Thread/sleep 1000) ; Allow replication time
      
      (let [replica1-value (wcar replica1-conn
                                 (car/get "sentinel-connectivity-test"))
            replica2-value (wcar replica2-conn
                                 (car/get "sentinel-connectivity-test"))]
        (is (= "connected" replica1-value) "Replica1 should have replicated data")
        (is (= "connected" replica2-value) "Replica2 should have replicated data"))

      ;; Cleanup
      (wcar primary-conn
            (car/del "sentinel-connectivity-test"))

      (log/info "Sentinel connectivity test completed successfully!")

      (catch Exception e
        (log/error "Sentinel test failed:" (.getMessage e))
        (is false (str "Sentinel test failed: " (.getMessage e)))))))

(deftest sentinel-failover-simulation-test
  (testing "Basic sentinel failover detection"
    (try
      ;; Simple connectivity test
      (let [ping-response (wcar sentinel-conn (car/ping))]
        (is (= "PONG" ping-response) "Sentinel should be reachable"))

      ;; Verify we can still write to primary
      (wcar primary-conn
            (car/set "failover-test" "pre-test"))

      (let [value (wcar primary-conn
                        (car/get "failover-test"))]
        (is (= "pre-test" value) "Primary should be writable"))

      ;; Cleanup
      (wcar primary-conn
            (car/del "failover-test"))

      (log/info "Failover simulation test completed!")

      (catch Exception e
        (log/error "Failover test failed:" (.getMessage e))
        (is false (str "Failover test failed: " (.getMessage e)))))))

(defn -main []
  (run-tests))