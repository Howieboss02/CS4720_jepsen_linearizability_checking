(ns jepsen.redis-sentinel.workloads
  (:require [clojure.core.async :as async :refer [go chan >! <! timeout]]
            [clojure.tools.logging :as log]))

(defn register-workload
  "Simple register read/write workload for linearizability testing"
  [duration-ms ops-per-second register-count]
  (let [ops-channel (chan 1000)
        interval-ms (/ 1000 ops-per-second)]
    (go
      (let [start-time (System/currentTimeMillis)
            end-time (+ start-time duration-ms)]
        (while (< (System/currentTimeMillis) end-time)
          (let [register-id (rand-int register-count)
                key (str "register-" register-id)
                op-type (if (< (rand) 0.7) :read :write)
                value (when (= op-type :write) (rand-int 1000))]
            (>! ops-channel {:type op-type :key key :value value}))
          (<! (timeout interval-ms)))
        (async/close! ops-channel)))
    ops-channel))

(defn counter-workload
  "Counter increment/read workload"
  [duration-ms ops-per-second counter-count]
  (let [ops-channel (chan 1000)
        interval-ms (/ 1000 ops-per-second)]
    (go
      (let [start-time (System/currentTimeMillis)
            end-time (+ start-time duration-ms)]
        (while (< (System/currentTimeMillis) end-time)
          (let [counter-id (rand-int counter-count)
                key (str "counter-" counter-id)
                op-type (if (< (rand) 0.6) :read :increment)]
            (>! ops-channel {:type op-type :key key}))
          (<! (timeout interval-ms)))
        (async/close! ops-channel)))
    ops-channel))

(defn mixed-workload
  "Mixed operations workload"
  [duration-ms ops-per-second key-count]
  (let [ops-channel (chan 1000)
        interval-ms (/ 1000 ops-per-second)]
    (go
      (let [start-time (System/currentTimeMillis)
            end-time (+ start-time duration-ms)]
        (while (< (System/currentTimeMillis) end-time)
          (let [key-id (rand-int key-count)
                key (str "key-" key-id)
                op-type (rand-nth [:read :write :increment :delete])
                value (when (= op-type :write) (rand-int 1000))]
            (>! ops-channel {:type op-type :key key :value value}))
          (<! (timeout interval-ms)))
        (async/close! ops-channel)))
    ops-channel))

;; NEW: Specialized workloads for read/write separation testing

(defn read-only-workload
  "Read-only workload for replica testing"
  [duration-ms ops-per-second key-count]
  (let [ops-channel (chan 1000)
        interval-ms (/ 1000 ops-per-second)]
    (go
      (let [start-time (System/currentTimeMillis)
            end-time (+ start-time duration-ms)]
        (while (< (System/currentTimeMillis) end-time)
          (let [key-id (rand-int key-count)
                key (str "key-" key-id)]
            (>! ops-channel {:type :read :key key}))
          (<! (timeout interval-ms)))
        (async/close! ops-channel)))
    ops-channel))

(defn write-only-workload
  "Write-only workload for primary testing"
  [duration-ms ops-per-second key-count]
  (let [ops-channel (chan 1000)
        interval-ms (/ 1000 ops-per-second)]
    (go
      (let [start-time (System/currentTimeMillis)
            end-time (+ start-time duration-ms)]
        (while (< (System/currentTimeMillis) end-time)
          (let [key-id (rand-int key-count)
                key (str "key-" key-id)
                op-type (rand-nth [:write :increment])
                value (when (= op-type :write) (rand-int 1000))]
            (>! ops-channel {:type op-type :key key :value value}))
          (<! (timeout interval-ms)))
        (async/close! ops-channel)))
    ops-channel))

(defn replica-stress-workload
  "High-volume read workload to stress replicas"
  [duration-ms ops-per-second key-count]
  (let [ops-channel (chan 2000)  ; Larger buffer for high volume
        interval-ms (/ 1000 ops-per-second)]
    (go
      (let [start-time (System/currentTimeMillis)
            end-time (+ start-time duration-ms)]
        (while (< (System/currentTimeMillis) end-time)
          (let [key-id (rand-int key-count)
                key (str "stress-key-" key-id)
                op-type (if (< (rand) 0.95) :read :scan)]  ; 95% reads, 5% scans
            (if (= op-type :scan)
              (>! ops-channel {:type :scan :pattern (str "stress-key-" (rand-int 10) "*")})
              (>! ops-channel {:type :read :key key})))
          (<! (timeout interval-ms)))
        (async/close! ops-channel)))
    ops-channel))