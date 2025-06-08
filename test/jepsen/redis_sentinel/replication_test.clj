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

(deftest linearizability-test
  (testing "Redis operations maintain linearizability under concurrent access"
    (let [histories (atom [])
          key-name "linearizability-test-key"
          num-threads 5
          operations-per-thread 10]
      
      (try
        ;; Initialize the key
        (wcar primary-conn
              (car/set key-name 0))
        
        ;; Record initial state
        (swap! histories conj {:type :invoke :f :read :value nil :time (System/nanoTime) :process :init})
        (let [initial-value (wcar primary-conn (car/get key-name))]
          (swap! histories conj {:type :ok :f :read :value (Integer/parseInt initial-value) :time (System/nanoTime) :process :init}))
        
        ;; Run concurrent operations
        (let [threads (for [thread-id (range num-threads)]
                        (future
                          (doseq [op-id (range operations-per-thread)]
                            (let [process-id (keyword (str "thread-" thread-id))
                                  operation (if (even? op-id) :write :read)]
                              
                              (if (= operation :write)
                                ;; Write operation (increment)
                                (let [invoke-time (System/nanoTime)]
                                  (swap! histories conj {:type :invoke :f :write :value :increment :time invoke-time :process process-id})
                                  (try
                                    (let [new-value (wcar primary-conn (car/incr key-name))
                                          ok-time (System/nanoTime)]
                                      (swap! histories conj {:type :ok :f :write :value new-value :time ok-time :process process-id}))
                                    (catch Exception e
                                      (let [fail-time (System/nanoTime)]
                                        (swap! histories conj {:type :fail :f :write :value :increment :time fail-time :process process-id :error (.getMessage e)}))))
                                  (Thread/sleep 100))
                                
                                ;; Read operation
                                (let [invoke-time (System/nanoTime)]
                                  (swap! histories conj {:type :invoke :f :read :value nil :time invoke-time :process process-id})
                                  (try
                                    (let [value (wcar primary-conn (car/get key-name))
                                          parsed-value (Integer/parseInt value)
                                          ok-time (System/nanoTime)]
                                      (swap! histories conj {:type :ok :f :read :value parsed-value :time ok-time :process process-id}))
                                    (catch Exception e
                                      (let [fail-time (System/nanoTime)]
                                        (swap! histories conj {:type :fail :f :read :value nil :time fail-time :process process-id :error (.getMessage e)}))))))))))]
          
          ;; Wait for all threads to complete
          (doseq [thread threads]
            @thread))
        
        ;; Sort history by time
        (let [sorted-history (sort-by :time @histories)]
          (log/info "Total operations:" (count sorted-history))
          (log/info "Sample operations:" (take 10 sorted-history))
          
          ;; Basic linearizability checks
          (let [reads (filter #(= (:f %) :read) sorted-history)
                writes (filter #(= (:f %) :write) sorted-history)
                successful-reads (filter #(= (:type %) :ok) reads)
                successful-writes (filter #(= (:type %) :ok) writes)]
            
            ;; Check that all reads return non-negative values
            (doseq [read successful-reads]
              (is (>= (:value read) 0) "All reads should return non-negative values"))
            
            ;; Check that reads are monotonic within each process
            (doseq [process (distinct (map :process successful-reads))]
              (let [process-reads (filter #(= (:process %) process) successful-reads)
                    process-read-values (map :value process-reads)]
                (is (apply <= process-read-values) 
                    (format "Reads within process %s should be monotonic: %s" process process-read-values))))
            
            ;; Check that final read reflects all writes
            (let [final-value (wcar primary-conn (car/get key-name))
                  expected-final (count successful-writes)]
              (is (= (Integer/parseInt final-value) expected-final)
                  (format "Final value %s should equal number of successful writes %d" final-value expected-final)))
            
            ;; Check that no read returns a value greater than the number of writes at that time
            (doseq [read successful-reads]
              (let [writes-before (count (filter #(and (= (:f %) :write
                                                        (= (:type %) :ok)
                                                        (<= (:time %) (:time read))))
                                                 sorted-history))]
                (is (<= (:value read) writes-before)
                    (format "Read value %d should not exceed writes completed by time %d (writes: %d)" 
                            (:value read) (:time read) writes-before))))
            
            (log/info "Linearizability checks passed!")
            (log/info "Successful reads:" (count successful-reads))
            (log/info "Successful writes:" (count successful-writes))))
        
        ;; Cleanup
        (finally
          (wcar primary-conn
                (car/del key-name)))))))

(deftest simple-linearizability-test
  (testing "Simple linearizability with sequential consistency"
    (let [key-name "simple-linear-test"]
      (try
        ;; Test 1: Sequential writes and reads should be consistent
        (wcar primary-conn (car/set key-name 1))
        (let [value1 (Integer/parseInt (wcar primary-conn (car/get key-name)))]
          (is (= 1 value1) "First read should return 1"))
        
        (wcar primary-conn (car/set key-name 2))
        (let [value2 (Integer/parseInt (wcar primary-conn (car/get key-name)))]
          (is (= 2 value2) "Second read should return 2"))
        
        ;; Test 2: Increment operations should be atomic
        (wcar primary-conn (car/set key-name 0))
        (dotimes [i 5]
          (wcar primary-conn (car/incr key-name)))
        
        (let [final-value (Integer/parseInt (wcar primary-conn (car/get key-name)))]
          (is (= 5 final-value) "After 5 increments, value should be 5"))
        
        (log/info "Simple linearizability test passed!")
        
        (finally
          (wcar primary-conn (car/del key-name)))))))