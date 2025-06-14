(ns jepsen.redis-sentinel.nemesis
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [jepsen.nemesis :as nemesis]
            [jepsen.control :as c]
            [jepsen.db :as db]))

;; Now you can use Jepsen's built-in network nemeses
(defn partition-nemesis
  "Creates network partitions between Redis nodes using Jepsen's implementation"
  []
  (nemesis/partition-random-halves))

(defn partition-primary-nemesis
  "Isolates the Redis primary from replicas using Jepsen's implementation"
  []
  (nemesis/partition-random-node))

;; Bridge nemesis for more complex partitions
(defn bridge-nemesis
  "Creates bridge partitions using Jepsen's implementation"
  []
  (nemesis/partition-majorities-ring))

;; Process control nemesis using Jepsen's approach
(defn kill-redis-nemesis
  "Randomly kills Redis processes using Jepsen's node control"
  []
  (nemesis/node-start-stopper
    identity
    (fn start [test node]
      (c/su (c/exec :systemctl :start :redis-server)))
    (fn stop [test node]
      (c/su (c/exec :systemctl :stop :redis-server)))))

(defn kill-sentinel-nemesis
  "Randomly kills Redis Sentinel processes using Jepsen's node control"
  []
  (nemesis/node-start-stopper
    identity
    (fn start [test node]
      (c/su (c/exec :systemctl :start :redis-sentinel)))
    (fn stop [test node]
      (c/su (c/exec :systemctl :stop :redis-sentinel)))))

;; Combined nemesis using Jepsen's compose
(defn combined-nemesis
  "Combines multiple nemesis types using Jepsen's framework"
  []
  (nemesis/compose
    {{:partition-start :partition-stop} (partition-nemesis)
     {:kill-redis-start :kill-redis-stop} (kill-redis-nemesis)
     {:kill-sentinel-start :kill-sentinel-stop} (kill-sentinel-nemesis)
     {:isolate-primary-start :isolate-primary-stop} (partition-primary-nemesis)}))