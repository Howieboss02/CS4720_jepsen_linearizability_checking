(ns jepsen.redis-sentinel.client
  (:require [clojure.tools.logging :as log]
            [taoensso.carmine :as car :refer [wcar]]
            [jepsen.redis-sentinel.test-harness :as harness]))

(defprotocol RedisClient
  (read-key [this key])
  (write-key [this key value])
  (cas-key [this key old-value new-value])
  (delete-key [this key])
  (increment-key [this key])
  (scan-keys [this pattern]))

(defrecord SmartRedisClient [primary-conn replica-conns client-id]
  RedisClient
  (read-key [this key]
    (let [start-time (harness/timestamp)
          ;; Use a random replica for reads
          conn (rand-nth replica-conns)]
      (harness/record-operation {:type :invoke :f :read :key key :client client-id 
                                :time start-time})
      (try
        (let [result (wcar conn (car/get key))
              end-time (harness/timestamp)]
          (harness/record-operation {:type :ok :f :read :key key :value result 
                                   :client client-id :time end-time})
          result)
        (catch Exception e
          (let [end-time (harness/timestamp)]
            (harness/record-operation {:type :fail :f :read :key key 
                                     :client client-id :time end-time 
                                     :error (.getMessage e)})
            (throw e))))))

  (write-key [this key value]
    (let [start-time (harness/timestamp)]
      (harness/record-operation {:type :invoke :f :write :key key :value value 
                                :client client-id :time start-time})
      (try
        ;; Always use primary for writes
        (let [result (wcar primary-conn (car/set key value))
              end-time (harness/timestamp)]
          (harness/record-operation {:type :ok :f :write :key key :value value 
                                   :client client-id :time end-time})
          result)
        (catch Exception e
          (let [end-time (harness/timestamp)]
            (harness/record-operation {:type :fail :f :write :key key :value value 
                                     :client client-id :time end-time 
                                     :error (.getMessage e)})
            (throw e))))))

  (increment-key [this key]
    (let [start-time (harness/timestamp)]
      (harness/record-operation {:type :invoke :f :increment :key key 
                                :client client-id :time start-time})
      (try
        ;; Always use primary for writes
        (let [result (wcar primary-conn (car/incr key))
              end-time (harness/timestamp)]
          (harness/record-operation {:type :ok :f :increment :key key :value result 
                                   :client client-id :time end-time})
          result)
        (catch Exception e
          (let [end-time (harness/timestamp)]
            (harness/record-operation {:type :fail :f :increment :key key 
                                     :client client-id :time end-time 
                                     :error (.getMessage e)})
            (throw e))))))

  (delete-key [this key]
    (let [start-time (harness/timestamp)]
      (harness/record-operation {:type :invoke :f :delete :key key 
                                :client client-id :time start-time})
      (try
        ;; Always use primary for writes
        (let [result (wcar primary-conn (car/del key))
              end-time (harness/timestamp)]
          (harness/record-operation {:type :ok :f :delete :key key 
                                   :deleted result :client client-id :time end-time})
          result)
        (catch Exception e
          (let [end-time (harness/timestamp)]
            (harness/record-operation {:type :fail :f :delete :key key 
                                     :client client-id :time end-time 
                                     :error (.getMessage e)})
            (throw e))))))

  (cas-key [this key old-value new-value]
    (let [start-time (harness/timestamp)]
      (harness/record-operation {:type :invoke :f :cas :key key 
                                :old-value old-value :new-value new-value 
                                :client client-id :time start-time})
      (try
        ;; CAS must use primary (requires WATCH/MULTI/EXEC)
        (let [result (wcar primary-conn 
                          (car/watch key)
                          (car/multi)
                          (when (= (car/get key) old-value)
                            (car/set key new-value))
                          (car/exec))
              end-time (harness/timestamp)
              success (not (nil? result))]
          (harness/record-operation {:type :ok :f :cas :key key 
                                   :old-value old-value :new-value new-value
                                   :success success :client client-id :time end-time})
          success)
        (catch Exception e
          (let [end-time (harness/timestamp)]
            (harness/record-operation {:type :fail :f :cas :key key 
                                     :old-value old-value :new-value new-value
                                     :client client-id :time end-time 
                                     :error (.getMessage e)})
            (throw e))))))

  (scan-keys [this pattern]
    (let [start-time (harness/timestamp)
          ;; Use a random replica for scans
          conn (rand-nth replica-conns)]
      (harness/record-operation {:type :invoke :f :scan :pattern pattern 
                                :client client-id :time start-time})
      (try
        (let [result (wcar conn (car/keys pattern))
              end-time (harness/timestamp)]
          (harness/record-operation {:type :ok :f :scan :pattern pattern 
                                   :keys result :count (count result)
                                   :client client-id :time end-time})
          result)
        (catch Exception e
          (let [end-time (harness/timestamp)]
            (harness/record-operation {:type :fail :f :scan :pattern pattern 
                                     :client client-id :time end-time 
                                     :error (.getMessage e)})
            (throw e)))))))

(defn create-client [client-id]
  "Creates a smart client that routes reads to replicas and writes to primary"
  (let [primary-conn (:primary harness/redis-nodes)
        replica-conns [(:replica1 harness/redis-nodes)
                      (:replica2 harness/redis-nodes)
                      (:replica3 harness/redis-nodes)
                      (:replica4 harness/redis-nodes)]]
    (->SmartRedisClient primary-conn replica-conns client-id)))