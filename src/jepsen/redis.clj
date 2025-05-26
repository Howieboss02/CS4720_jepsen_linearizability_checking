(ns jepsen.redis
    (:require [clojure.tools.logging :refer :all]
              [clojure.string :as str]
              [jepsen.core :as jepsen]
              [jepsen.client :as client]
              [jepsen.generator :as gen]
              [jepsen.model :as model]
              [jepsen.os.debian :as debian]
              [jepsen.checker :as checker]
              [jepsen.nemesis :as nemesis]
              [jepsen.util :refer [timeout]]
              [knossos.model :as kmodel]
              [taoensso.carmine :as car :refer (wcar)]
              [clojure.set :as set]))

(def redis-port 6379)
(def sentinel-port 26379)

(defn sentinel-conn-spec
  "Connection spec for Redis Sentinel"
  [node]
  {:pool {}
   :spec {:host node
          :port sentinel-port
          :timeout-ms 5000}})

(defn redis-conn-spec
  "Connection spec for Redis"
  [node]
  {:pool {}
   :spec {:host node
          :port redis-port
          :timeout-ms 5000}})

(defn current-master
  "Ask Sentinel for the current master"
  [sentinel-node]
  (try
    (let [conn (sentinel-conn-spec sentinel-node)]
      (wcar conn
            (car/raw "SENTINEL" "get-master-addr-by-name" "mymaster")))
    (catch Exception e
      (warn "Failed to get master from sentinel" sentinel-node ":" (.getMessage e))
      nil)))

(defn find-master
  "Find the current Redis master by asking all sentinels"
  [nodes]
  (loop [nodes nodes]
    (when (seq nodes)
          (if-let [master (current-master (first nodes))]
            (first master)
            (recur (rest nodes))))))

(defrecord RedisSetClient [nodes]
  client/Client
  (setup! [this test node]
    (info "Setting up Redis client on" node)
    this)

  (invoke! [this test op]
    (case (:f op)
          :add (try
                 ;; Find current master by asking sentinels
                 (if-let [master (find-master nodes)]
                   (let [conn (redis-conn-spec master)]
                     (wcar conn
                           (car/sadd "jepsen-set" (:value op)))
                     (assoc op :type :ok))
                   (assoc op :type :fail :error "No master found"))
                 (catch Exception e
                   (warn "Add failed:" (.getMessage e))
                   (assoc op :type :fail :error (.getMessage e))))

          :read (try
                  ;; Read from any available node (could be stale)
                  (let [master (or (find-master nodes) (first nodes))
                        conn (redis-conn-spec master)]
                    (let [members (wcar conn (car/smembers "jepsen-set"))]
                      (assoc op :type :ok :value (set (map #(Long/parseLong %) members)))))
                  (catch Exception e
                    (warn "Read failed:" (.getMessage e))
                    (assoc op :type :fail :error (.getMessage e))))))

  (teardown! [this test]
    (info "Tearing down Redis client")))

(defn redis-set-client
  "Creates a Redis set client"
  [nodes]
  (RedisSetClient. nodes))

(defn adds
  "Generator for add operations"
  []
  (->> (range)
       (map (fn [x] {:type :invoke :f :add :value x}))))

(defn reads
  "Generator for read operations"
  []
  (repeat {:type :invoke :f :read}))

(defn redis-test
  "Constructs a Jepsen test for Redis"
  [version]
  {:name "redis-set"
   :os debian/os
   :db (jepsen.redis.db/db version)
   :client (redis-set-client ["n1" "n2" "n3" "n4" "n5"])
   :generator (gen/phases
               (->> (gen/mix [adds reads])
                    (gen/stagger 1/10)
                    (gen/time-limit 60))
               (gen/nemesis
                (gen/seq (cycle [(gen/sleep 30)
                                 {:type :info :f :start}
                                 (gen/sleep 200)
                                 {:type :info :f :stop}])))
               (gen/log "Healing cluster")
               (gen/sleep 10)
               (gen/clients
                (gen/each
                 (gen/once {:type :invoke :f :read}))))
   :nemesis (nemesis/partition-random-halves)
   :model (model/set)
   :checker (checker/compose
             {:perf (checker/perf)
              :set (checker/set)})})

(defn -main
  "Entry point for the Redis Jepsen test"
  [& args]
  (jepsen/run! (redis-test "2.6.13")))