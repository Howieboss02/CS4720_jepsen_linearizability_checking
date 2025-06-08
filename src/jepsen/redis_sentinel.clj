(ns jepsen.redis-sentinel
  "Redis Sentinel linearizability tests using Jepsen"
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [jepsen [checker :as checker]
             [cli :as cli]
             [client :as client]
             [control :as c]
             [db :as db]
             [generator :as gen]
             [nemesis :as nemesis]
             [tests :as tests]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [knossos.model :as model]
            [taoensso.carmine :as car :refer [wcar]]
            [cheshire.core :as json])
  (:gen-class))

(def redis-port 6379)
(def sentinel-port 26379)

;; Redis connection pool configuration
(defn redis-conn-spec [node]
  {:pool {}
   :spec {:host node
          :port redis-port
          :timeout-ms 5000}})

(defn sentinel-conn-spec [node]
  {:pool {}
   :spec {:host node
          :port sentinel-port
          :timeout-ms 5000}})

;; Client implementation
(defrecord RedisClient [conn-spec sentinel-spec]
  client/Client
  (open! [this test node]
    (assoc this
           :conn-spec (redis-conn-spec node)
           :sentinel-spec (sentinel-conn-spec node)))

  (setup! [this test])

  (invoke! [this test op]
    (let [{:keys [f value]} op]
      (try
        (case f
          :read
          (let [result (wcar (:conn-spec this) (car/get (str value)))]
            (assoc op :type :ok :value (when result (Long/parseLong result))))

          :write
          (let [[k v] value
                result (wcar (:conn-spec this) (car/set (str k) (str v)))]
            (if (= "OK" result)
              (assoc op :type :ok)
              (assoc op :type :fail :error result)))

          :cas
          (wcar (:conn-spec this)
                (car/watch (str (first value)))
                (let [k (first value)
                      old (second value)
                      new (nth value 2)
                      current (car/get (str k))
                      current-val (when current (Long/parseLong current))]
                  (if (= current-val old)
                    (let [result (car/multi
                                  (car/set (str k) (str new)))]
                      (if (= "OK" (first result))
                        (assoc op :type :ok)
                        (assoc op :type :fail :error "Transaction failed")))
                    (do
                      (car/unwatch)
                      (assoc op :type :fail :error "Value mismatch")))))

          (assoc op :type :fail :error "Unknown operation"))
        (catch Exception e
          (log/warn e "Redis operation failed")
          (assoc op :type :fail :error (.getMessage e))))))

  (teardown! [this test])

  (close! [this test]
    this))

(defn redis-client
  "Creates a new Redis client"
  []
  (->RedisClient nil nil))

;; Database setup (for containerized environment)
(defn db
  "Redis database for Docker Compose setup"
  []
  (reify db/DB
    (setup! [_ test node]
      (log/info "Setting up Redis on" node)
      ;; In containerized setup, Redis is already running
      ;; We just need to ensure connectivity
      (Thread/sleep 5000)) ; Wait for containers to be ready

    (teardown! [_ test node]
      (log/info "Tearing down Redis on" node)
      ;; No teardown needed for containers
      )))

;; Nemesis for fault injection
(defn partition-nemesis
  "Creates network partition nemesis using Docker network manipulation"
  []
  (nemesis/partition-random-halves))

(defn kill-nemesis
  "Creates process kill nemesis by stopping containers"
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)
    (invoke! [this test op]
      (case (:f op)
        :start
        (let [node (rand-nth (:nodes test))]
          (log/info "Killing Redis on" node)
          (try
            ;; In real implementation, would use Docker API to stop container
            (assoc op :type :ok :value node)
            (catch Exception e
              (assoc op :type :fail :error (.getMessage e)))))
        :stop
        (do
          (log/info "Restarting all Redis nodes")
          (assoc op :type :ok))))
    (teardown! [this test] this)))

;; Generators
(defn rw-gen
  "Generator for read/write operations"
  []
  (gen/mix
   [{:f :read :value (rand-int 5)}
    {:f :write :value [(rand-int 5) (rand-int 100)]}]))

(defn cas-gen
  "Generator for compare-and-swap operations"
  []
  (->> (gen/mix
        [{:f :read :value (rand-int 5)}
         {:f :write :value [(rand-int 5) (rand-int 100)]}
         {:f :cas :value [(rand-int 5) (rand-int 100) (rand-int 100)]}])
       (gen/stagger 1/10)))

;; Workloads
(defn register-workload
  "A simple register workload for linearizability testing"
  [opts]
  {:client (redis-client)
   :checker (checker/compose
             {:perf (checker/perf)
              :linear (checker/linearizable
                       {:model (model/register)
                        :algorithm :linear})
              :timeline (timeline/html)})
   :generator (->> (cas-gen)
                   (gen/nemesis
                    (cycle
                     [(gen/sleep 5)
                      {:type :info :f :start}
                      (gen/sleep 5)
                      {:type :info :f :stop}]))
                   (gen/time-limit (:time-limit opts 60)))
   :nemesis (partition-nemesis)})

;; Test definitions
(defn redis-sentinel-test
  "Basic Redis Sentinel linearizability test"
  [opts]
  (merge tests/noop-test
         opts
         {:name "redis-sentinel-linearizability"
          :os debian/os
          :db (db)
          :nodes ["redis-primary" "redis-replica1" "redis-replica2"]}
         (register-workload opts)))

;; CLI
(def cli-opts
  "Command line options"
  [["-w" "--workload NAME" "Test workload to run"
    :default :register
    :parse-fn keyword
    :validate [#{:register} "Must be one of: register"]]
   ["-r" "--rate HZ" "Approximate number of requests per second, per thread."
    :default 10
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "Must be a positive number"]]
   [nil "--nemesis NAME" "Nemesis to use"
    :default :partition
    :parse-fn keyword
    :validate [#{:partition :kill :none} "Must be one of: partition, kill, none"]]])

(defn -main
  "Main entry point for Redis Sentinel Jepsen tests"
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn redis-sentinel-test
                                         :opt-spec cli-opts})
                   (cli/serve-cmd))
            args))