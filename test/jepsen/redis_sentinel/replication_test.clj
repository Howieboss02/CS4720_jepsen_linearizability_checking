(ns jepsen.redis-sentinel.replication-test
  (:require [clojure.test :refer :all]
            [taoensso.carmine :as car :refer [wcar]]
            [clojure.tools.logging :as log]))

(def primary-conn {:pool {} :spec {:host "redis-primary" :port 6379}})
(def replica1-conn {:pool {} :spec {:host "redis-replica1" :port 6379}})
(def replica2-conn {:pool {} :spec {:host "redis-replica2" :port 6379}})

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

(defn -main []
  (run-tests))