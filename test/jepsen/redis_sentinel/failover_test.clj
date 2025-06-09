(ns jepsen.redis-sentinel.failover-test
  (:require [clojure.test :refer :all]
            [taoensso.carmine :as car :refer [wcar]]
            [clojure.tools.logging :as log]
            [clojure.java.shell :as shell]))

;; Dynamic connection discovery (fixed approach)
(def sentinel-conn {:pool {} :spec {:host "redis-sentinel1" :port 26379}})

(defn get-current-primary []
  "Discover current primary from Sentinel"
  (println "Starting primary discovery from Sentinel...")
  (println "Sentinel connection spec:" sentinel-conn)
  (try
    (println "Querying Sentinel for master 'mymaster'...")
    (let [master-info (wcar sentinel-conn 
                           (car/redis-call ["SENTINEL" "get-master-addr-by-name" "mymaster"]))]
      (println "Raw master info from Sentinel:" master-info)
      (println "Master info type:" (type master-info))
      (println "Master info count:" (when master-info (count master-info)))
      
      (if master-info
        (let [host (first master-info)
              port-str (second master-info)
              port (Integer/parseInt port-str)
              conn-spec {:pool {} :spec {:host host :port port}}]
          (println "Parsed master host:" host)
          (println "Parsed master port:" port)
          (println "Created connection spec:" conn-spec)
          conn-spec)
        (do
          (println "ERROR: No master info returned from Sentinel!")
          (throw (Exception. "No master found")))))
        
    (catch NumberFormatException e
      (println "ERROR: Failed to parse master port as integer:" (.getMessage e))
      (throw e))
    (catch Exception e
      (println "ERROR: Failed to get master from Sentinel:" (.getMessage e))
      (println "Exception type:" (type e))
      (println "Full exception:" e)
      (throw e))))

(defn get-replica-connections []
  "Get all replica connections from Sentinel"
  (try
    (let [replicas (wcar sentinel-conn 
                        (car/redis-call ["SENTINEL" "replicas" "mymaster"]))]
      (for [replica-info replicas]
        (let [info-map (apply hash-map replica-info)
              host (get info-map "ip")
              port (Integer/parseInt (get info-map "port"))]
          {:pool {} :spec {:host host :port port}})))
    (catch Exception e
      (println "Failed to get replicas:" (.getMessage e))
      [])))

(defn simulate-primary-failure! []
  "Kill the current primary container"
  (try
    ;; Get current primary info first
    (let [current-primary (get-current-primary)
          primary-host (get-in current-primary [:spec :host])]
      (println "Killing primary:" primary-host)
      
      ;; Kill the container - adjust container name based on your docker-compose
      (let [result (shell/sh "docker" "kill" primary-host)]
        (if (= 0 (:exit result))
          (println "Primary killed successfully")
          (println "Failed to kill primary:" (:err result)))))
    (catch Exception e
      (println "Failed to simulate failure:" (.getMessage e)))))

(defn wait-for-failover [max-wait-ms]
  "Wait for Sentinel to complete failover to a new primary"
  (let [start-time (System/currentTimeMillis)
        original-primary (try (get-current-primary) (catch Exception e nil))
        original-host (when original-primary (get-in original-primary [:spec :host]))]
    (println "Waiting for failover from original primary:" original-host)
    (loop [attempt 0]
      (let [elapsed (- (System/currentTimeMillis) start-time)]
        (if (>= elapsed max-wait-ms)
          (throw (Exception. "Failover timeout"))
          (let [current-primary-result (try 
                                        (get-current-primary)
                                        (catch Exception e 
                                          (println "Attempt" attempt ": Failed to get current primary:" (.getMessage e))
                                          nil))]
            (if current-primary-result
              (let [current-host (get-in current-primary-result [:spec :host])]
                (println "Attempt" attempt ": Current primary is" current-host)
                (if (and current-host 
                         original-host
                         (not= current-host original-host))
                  (do
                    (println "Failover completed! New primary:" current-host)
                    current-primary-result)
                  (do
                    (println "Still waiting for failover... sleeping 2 seconds")
                    (Thread/sleep 2000)
                    (recur (inc attempt)))))
              (do
                (println "No primary found, sleeping 2 seconds...")
                (Thread/sleep 2000)
                (recur (inc attempt))))))))))

(deftest resilient-replication-test
  (testing "Write-read test that uses dynamic primary discovery"
    (try
      ;; Get current primary dynamically
      (let [primary-conn (get-current-primary)]
        (println "Current primary:" (get-in primary-conn [:spec :host]))
        
        ;; Write to current primary
        (doseq [i (range 1 6)]
          (wcar primary-conn 
                (car/set (str "resilient-" i) i))
          (println "Wrote" i "to current primary"))
        
        ;; Wait for replication
        (Thread/sleep 3000)
        
        ;; Verify on all replicas
        (let [replica-conns (get-replica-connections)]
          (println "Found" (count replica-conns) "replicas")
          (doseq [replica-conn replica-conns
                  i (range 1 6)]
            (let [value (wcar replica-conn (car/get (str "resilient-" i)))]
              (is (= (str i) value) 
                  (format "Value %d should be replicated" i)))))
        
        (println "Resilient replication test passed!")
        
        ;; Cleanup
        (doseq [i (range 1 6)]
          (wcar primary-conn (car/del (str "resilient-" i)))))
      
      (catch Exception e
        (println "Test failed:" (.getMessage e))
        (is false (str "Test failed: " (.getMessage e)))))))

(deftest actual-failover-test
  (testing "Data survives primary failure and writes continue on new primary"
    (let [test-key "failover-survival-test"]
      (try
        ;; Phase 1: Write to original primary
        (let [original-primary (get-current-primary)
              original-host (get-in original-primary [:spec :host])]
          (wcar original-primary (car/set test-key "before-failover"))
          (println "Wrote test data to original primary:" original-host))
        
        ;; Wait for replication
        (Thread/sleep 2000)
        
        ;; Phase 2: Simulate primary failure
        (simulate-primary-failure!)
        (println "Simulated primary failure")
        
        ;; Phase 3: Wait for failover (max 30 seconds)
        (let [new-primary (wait-for-failover 30000)
              new-host (get-in new-primary [:spec :host])]
          (println "Failover completed! New primary:" new-host)
          
          ;; Phase 4: Verify data survived failover
          (let [value (wcar new-primary (car/get test-key))]
            (is (= "before-failover" value) 
                "Data should survive failover"))
          
          ;; Phase 5: Verify new primary accepts writes
          (wcar new-primary (car/set test-key "after-failover"))
          (let [new-value (wcar new-primary (car/get test-key))]
            (is (= "after-failover" new-value) 
                "New primary should accept writes"))
          
          ;; Phase 6: Verify replicas get the new data
          (Thread/sleep 3000)
          (let [replica-conns (get-replica-connections)]
            (doseq [replica-conn replica-conns]
              (let [replica-value (wcar replica-conn (car/get test-key))]
                (is (= "after-failover" replica-value)
                    "Replicas should get data from new primary"))))
          
          (println "Failover test completed successfully!")
          
          ;; Cleanup
          (wcar new-primary (car/del test-key)))
        
        (catch Exception e
          (println "Failover test failed:" (.getMessage e))
          (is false (str "Failover test failed: " (.getMessage e))))))))

(deftest sentinel-master-discovery-test
  (testing "Sentinel can discover current master information"
    (try
      ;; Test master discovery - FIXED: Use redis-call with vector
      (let [master-info (wcar sentinel-conn 
                             (car/redis-call ["SENTINEL" "get-master-addr-by-name" "mymaster"]))]
        (is (not (nil? master-info)) "Should get master info from Sentinel")
        (is (= 2 (count master-info)) "Should get host and port")
        (println "Current master from Sentinel:" master-info))
      
      ;; Test replica discovery - FIXED: Use redis-call with vector
      (let [replicas (wcar sentinel-conn 
                          (car/redis-call ["SENTINEL" "replicas" "mymaster"]))]
        (is (not (empty? replicas)) "Should find replicas")
        (println "Found" (count replicas) "replicas"))
      
      ;; Test getting primary dynamically
      (let [primary-conn (get-current-primary)]
        (is (not (nil? primary-conn)) "Should get primary connection")
        
        ;; Test we can write to discovered primary
        (wcar primary-conn (car/set "discovery-test" "works"))
        (let [value (wcar primary-conn (car/get "discovery-test"))]
          (is (= "works" value) "Should be able to use discovered primary")
          (wcar primary-conn (car/del "discovery-test"))))
      
      (println "Sentinel discovery test passed!")
      
      (catch Exception e
        (println "Discovery test failed:" (.getMessage e))
        (is false (str "Discovery test failed: " (.getMessage e)))))))

(deftest test-get-current-primary
  (testing "Test get-current-primary function directly"
    (try
      (let [primary (get-current-primary)]
        (println "Successfully got primary:" primary)
        (is (not (nil? primary)) "Should return a primary connection"))
      (catch Exception e
        (println "Function failed with error:" (.getMessage e))
        (is false (str "get-current-primary failed: " (.getMessage e)))))))

(defn -main []
  (run-tests))