(ns jepsen.redis-sentinel.fault-injection
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [go chan >! <!]]
            [clojure.java.shell :as shell]  ; Add this line
            [jepsen.redis-sentinel.client :as client]))

(defn execute-operation-with-retry
  "Execute operation with retry logic for fault tolerance"
  [client operation max-retries]
  (letfn [(attempt [retries]
            (try
              (case (:type operation)
                :read (client/read-key client (:key operation))
                :write (client/write-key client (:key operation) (:value operation))
                :increment (client/increment-key client (:key operation))
                :delete (client/delete-key client (:key operation))
                :scan (client/scan-keys client (:pattern operation "key-*"))
                (log/warn "Unknown operation type:" (:type operation)))
              (catch Exception e
                (if (< retries max-retries)
                  (do
                    (log/debug "Operation failed, retrying" (inc retries) "/" max-retries)
                    (Thread/sleep (* 1000 (inc retries)))  ; Exponential backoff
                    (attempt (inc retries)))
                  (throw e)))))]
    (attempt 0)))

(defn inject-network-partition
  "Inject network partition fault"
  [duration]
  (log/info "Injecting network partition for" duration "ms")
  (try
    ;; Simulate network partition using Docker network manipulation
    (let [partition-cmd "docker network disconnect redis-network jepsen-redis-replica1"]
      (shell/sh "bash" "-c" partition-cmd))  ; Use shell/sh instead
    
    (Thread/sleep duration)
    
    ;; Heal partition
    (let [heal-cmd "docker network connect redis-network jepsen-redis-replica1"]
      (shell/sh "bash" "-c" heal-cmd))  ; Use shell/sh instead
    
    (log/info "Network partition healed")
    (catch Exception e
      (log/error "Network partition injection failed:" e))))

(defn inject-process-crash
  "Inject process crash fault"
  [duration]
  (log/info "Injecting process crash for" duration "ms")
  (try
    ;; Stop a random Redis node
    (let [nodes ["jepsen-redis-replica1" "jepsen-redis-replica2" 
                 "jepsen-redis-replica3" "jepsen-redis-replica4"]
          target-node (rand-nth nodes)
          stop-cmd (str "docker stop " target-node)]
      
      (shell/sh "bash" "-c" stop-cmd)  ; Use shell/sh instead
      (log/info "Stopped node:" target-node)
      
      (Thread/sleep duration)
      
      ;; Restart the node
      (let [start-cmd (str "docker start " target-node)]
        (shell/sh "bash" "-c" start-cmd))  ; Use shell/sh instead
      
      (log/info "Restarted node:" target-node))
    (catch Exception e
      (log/error "Process crash injection failed:" e))))

(defn inject-network-delay
  "Inject network delay fault"
  [duration]
  (log/info "Injecting network delay for" duration "ms")
  (try
    ;; Add network delay using tc (traffic control)
    (let [delay-cmd "docker exec jepsen-redis-replica1 tc qdisc add dev eth0 root netem delay 200ms 50ms"]
      (shell/sh "bash" "-c" delay-cmd))  ; Use shell/sh instead
    
    (Thread/sleep duration)
    
    ;; Remove network delay
    (let [remove-cmd "docker exec jepsen-redis-replica1 tc qdisc del dev eth0 root"]
      (shell/sh "bash" "-c" remove-cmd))  ; Use shell/sh instead
    
    (log/info "Network delay removed")
    (catch Exception e
      (log/error "Network delay injection failed:" e))))

(defn inject-packet-loss
  "Inject packet loss fault"
  [duration]
  (log/info "Injecting packet loss for" duration "ms")
  (try
    ;; Add packet loss using tc
    (let [loss-cmd "docker exec jepsen-redis-replica2 tc qdisc add dev eth0 root netem loss 10%"]
      (shell/sh "bash" "-c" loss-cmd))  ; Use shell/sh instead
    
    (Thread/sleep duration)
    
    ;; Remove packet loss
    (let [remove-cmd "docker exec jepsen-redis-replica2 tc qdisc del dev eth0 root"]
      (shell/sh "bash" "-c" remove-cmd))  ; Use shell/sh instead
    
    (log/info "Packet loss removed")
    (catch Exception e
      (log/error "Packet loss injection failed:" e))))

(defn inject-sentinel-failure
  "Inject Sentinel failure"
  [duration]
  (log/info "Injecting Sentinel failure for" duration "ms")
  (try
    ;; Stop a random Sentinel
    (let [sentinels ["jepsen-redis-sentinel1" "jepsen-redis-sentinel2" "jepsen-redis-sentinel3"]
          target-sentinel (rand-nth sentinels)
          stop-cmd (str "docker stop " target-sentinel)]
      
      (shell/sh "bash" "-c" stop-cmd)  ; Use shell/sh instead
      (log/info "Stopped Sentinel:" target-sentinel)
      
      (Thread/sleep duration)
      
      ;; Restart the Sentinel
      (let [start-cmd (str "docker start " target-sentinel)]
        (shell/sh "bash" "-c" start-cmd))  ; Use shell/sh instead
      
      (log/info "Restarted Sentinel:" target-sentinel))
    (catch Exception e
      (log/error "Sentinel failure injection failed:" e))))

(defn inject-primary-isolation
  "Inject primary isolation fault"
  [duration]
  (log/info "Injecting primary isolation for" duration "ms")
  (try
    ;; Isolate primary from network
    (let [isolate-cmd "docker network disconnect redis-network jepsen-redis-primary"]
      (shell/sh "bash" "-c" isolate-cmd))  ; Use shell/sh instead
    
    (Thread/sleep duration)
    
    ;; Reconnect primary
    (let [reconnect-cmd "docker network connect redis-network jepsen-redis-primary"]
      (shell/sh "bash" "-c" reconnect-cmd))  ; Use shell/sh instead
    
    (log/info "Primary reconnected to network")
    (catch Exception e
      (log/error "Primary isolation injection failed:" e))))

(defn inject-random-fault
  "Inject a random fault type"
  [duration]
  (let [fault-types [:network-partition :process-crash :network-delay 
                     :packet-loss :sentinel-failure]
        chosen-fault (rand-nth fault-types)]
    (log/info "Injecting random fault:" chosen-fault)
    (case chosen-fault
      :network-partition (inject-network-partition duration)
      :process-crash (inject-process-crash duration)
      :network-delay (inject-network-delay duration)
      :packet-loss (inject-packet-loss duration)
      :sentinel-failure (inject-sentinel-failure duration))))

(defn start-fault-injection
  "Starts fault injection based on type and frequency"
  [fault-type fault-frequency fault-duration test-duration]
  (go
    (let [start-time (System/currentTimeMillis)
          end-time (+ start-time test-duration)
          fault-interval (* 1000 fault-frequency)]  ; Convert to ms

      (while (< (System/currentTimeMillis) end-time)
        (try
          (case fault-type
            :network-partition (inject-network-partition fault-duration)
            :process-crash (inject-process-crash fault-duration)
            :network-delay (inject-network-delay fault-duration)
            :packet-loss (inject-packet-loss fault-duration)
            :sentinel-failure (inject-sentinel-failure fault-duration)
            :primary-isolation (inject-primary-isolation fault-duration)
            :chaos (inject-random-fault fault-duration)
            (log/warn "Unknown fault type:" fault-type))
          (catch Exception e
            (log/error "Fault injection failed:" e)))

        (<! (async/timeout fault-interval))))))