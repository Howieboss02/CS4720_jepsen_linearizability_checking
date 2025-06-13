(ns jepsen.redis-sentinel.nemesis
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [jepsen.nemesis :as nemesis]
            [jepsen.control :as c]
            [jepsen.db :as db]
            [clojure.core.async :as async :refer [go chan >! <!]]))

;; Network partition nemesis
(defn partition-nemesis
  "Creates network partitions between Redis nodes"
  []
  (nemesis/partition-random-halves))

(defn partition-primary-nemesis
  "Isolates the Redis primary from replicas"
  []
  (nemesis/partition-random-node))

;; Process failure nemesis
(defn kill-redis-nemesis
  "Randomly kills Redis processes"
  []
  (nemesis/node-start-stopper
    (fn start [test node]
      (c/su (c/exec :docker :start (str "jepsen-redis-" (name node)))))
    (fn stop [test node]
      (c/su (c/exec :docker :stop (str "jepsen-redis-" (name node)))))))

(defn kill-sentinel-nemesis
  "Randomly kills Redis Sentinel processes"
  []
  (nemesis/node-start-stopper
    (fn start [test node]
      (c/su (c/exec :docker :start (str "jepsen-redis-sentinel" (last (name node))))))
    (fn stop [test node]
      (c/su (c/exec :docker :stop (str "jepsen-redis-sentinel" (last (name node))))))))

;; Combined nemesis for multiple failure types
(defn combined-nemesis
  "Combines multiple nemesis types for comprehensive fault injection"
  []
  (nemesis/compose
    {{:partition-start :partition-stop} (partition-nemesis)
     {:kill-redis-start :kill-redis-stop} (kill-redis-nemesis)
     {:kill-sentinel-start :kill-sentinel-stop} (kill-sentinel-nemesis)
     {:isolate-primary-start :isolate-primary-stop} (partition-primary-nemesis)}))

;; Network delay nemesis
(defn network-delay-nemesis
  "Introduces network delays between nodes"
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)
    (invoke! [this test op]
      (case (:f op)
        :start-delay (do
                      (log/info "Introducing network delays")
                      (c/on-nodes test (:nodes test)
                        (fn [test node]
                          (c/su
                            (c/exec :tc :qdisc :add :dev :eth0 :root :netem 
                                   :delay "100ms" "50ms" :distribution :normal))))
                      (assoc op :type :ok :value "Network delays started"))
        :stop-delay (do
                     (log/info "Removing network delays")
                     (c/on-nodes test (:nodes test)
                       (fn [test node]
                         (c/su (c/exec :tc :qdisc :del :dev :eth0 :root))))
                     (assoc op :type :ok :value "Network delays stopped"))))
    (teardown! [this test])))

;; Packet loss nemesis
(defn packet-loss-nemesis
  "Introduces packet loss between nodes"
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)
    (invoke! [this test op]
      (case (:f op)
        :start-loss (do
                     (log/info "Introducing packet loss")
                     (c/on-nodes test (:nodes test)
                       (fn [test node]
                         (c/su
                           (c/exec :tc :qdisc :add :dev :eth0 :root :netem 
                                  :loss "5%"))))
                     (assoc op :type :ok :value "Packet loss started"))
        :stop-loss (do
                    (log/info "Removing packet loss")
                    (c/on-nodes test (:nodes test)
                      (fn [test node]
                        (c/su (c/exec :tc :qdisc :del :dev :eth0 :root))))
                    (assoc op :type :ok :value "Packet loss stopped"))))
    (teardown! [this test])))

;; Chaos nemesis - combines all failure types
(defn chaos-nemesis
  "Comprehensive chaos nemesis with multiple failure modes"
  []
  (nemesis/compose
    {{:partition-start :partition-stop} (partition-nemesis)
     {:kill-redis-start :kill-redis-stop} (kill-redis-nemesis)
     {:kill-sentinel-start :kill-sentinel-stop} (kill-sentinel-nemesis)
     {:delay-start :delay-stop} (network-delay-nemesis)
     {:loss-start :loss-stop} (packet-loss-nemesis)
     {:isolate-primary-start :isolate-primary-stop} (partition-primary-nemesis)}))