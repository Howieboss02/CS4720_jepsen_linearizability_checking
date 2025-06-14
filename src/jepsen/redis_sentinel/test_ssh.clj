(ns jepsen.redis-sentinel.test-ssh
  (:require [clojure.tools.logging :refer :all]
            [jepsen [checker :as checker]
             [client :as client]
             [control :as c]
             [db :as db]
             [generator :as gen]
             [tests :as tests]
             [core :as jepsen]
             [nemesis :as nemesis]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.net :as net]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [knossos.model :as model]
            [taoensso.carmine :as car :refer [wcar]]))

(defn redis-db []
  (reify db/DB
    (setup! [_ test node]
      (info "Setting up Redis on" node)
      (c/exec :redis-cli :flushall))

    (teardown! [_ test node]
      (info "Cleaning up Redis on" node))))

(defn redis-client []
  (let [conn (atom nil)]
    (reify client/Client
      (open! [this test node]
        (reset! conn {:pool {} :spec {:host node :port 6379}})
        this)

      (setup! [this test])

      (invoke! [this test operation]
        (try
          (case (:f operation)
            :read
            (let [value (wcar @conn (car/get "test-key"))]
              (assoc operation :type :ok :value value))

            :write
            (do
              (wcar @conn (car/set "test-key" (:value operation)))
              (assoc operation :type :ok)))

          (catch Exception e
            (assoc operation :type :fail :error (.getMessage e)))))

      (teardown! [this test])
      (close! [this test]))))

(defn redis-sentinel-client []
  "Client that uses Redis Sentinel for primary/replica distinction"
  (let [conn (atom nil)
        primary-node (atom "n1")  ; Start with n1 as assumed primary
        replica-nodes (atom ["n2" "n3" "n4" "n5"])]
    (reify client/Client
      (open! [this test node]
        (reset! conn {:pool {} :spec {:host node :port 6379}})
        this)

      (setup! [this test]
        (info "Setting up Redis Sentinel client"))

      (invoke! [this test operation]
        (try
          (case (:f operation)
            :read
            ;; Read from any replica (round-robin through replicas)
            (let [replica (rand-nth @replica-nodes)
                  replica-conn {:pool {} :spec {:host replica :port 6379}}
                  value (wcar replica-conn (car/get "test-key"))]
              (info "Reading from replica" replica "value:" value)
              (assoc operation :type :ok :value value :node replica))

            :write
            ;; Write only to primary
            (let [primary @primary-node
                  primary-conn {:pool {} :spec {:host primary :port 6379}}]
              (wcar primary-conn (car/set "test-key" (:value operation)))
              (info "Writing to primary" primary "value:" (:value operation))
              (assoc operation :type :ok :node primary)))

          (catch Exception e
            (error "Operation failed:" (.getMessage e))
            (assoc operation :type :fail :error (.getMessage e)))))

      (teardown! [this test]
        (info "Tearing down Redis Sentinel client"))

      (close! [this test]
        (info "Closing Redis Sentinel client")))))

(defn simple-test []
  {:name "redis-simple-test"
   :os debian/os
   :db (redis-db)
   :client (redis-client)
   :nemesis nemesis/noop
   :concurrency 3
   :nodes ["n1" "n2" "n3" "n4" "n5"]
   ;; SSH configuration
   :remote c/ssh
   :username "root"
   :private-key-path "/root/.ssh/id_rsa"
   :strict-host-key-checking false
   :ssh-opts ["-o" "StrictHostKeyChecking=no"
              "-o" "UserKnownHostsFile=/dev/null"
              "-o" "GlobalKnownHostsFile=/dev/null"
              "-o" "LogLevel=ERROR"]
   ;; Fixed generator - use cycle to repeat operations indefinitely
   :generator (->> (cycle [{:type :invoke :f :read}
                           {:type :invoke :f :write :value (rand-int 100)}])
                   (gen/stagger 1/10)  ; 10 ops per second per thread
                   (gen/time-limit 30))  ; Run for 30 seconds
   :checker (checker/compose
             {:perf (checker/perf)
              :timeline (timeline/html)
              :linear (checker/linearizable {:model (model/register)})})})

(defn intensive-test []
  "More intensive test with primary/replica distinction for 3 minutes"
  {:name "redis-intensive-test"
   :os debian/os
   :db (redis-db)
   :client (redis-sentinel-client)  ; Use sentinel-aware client
   :nemesis nemesis/noop
   :concurrency 15  ; More concurrent threads
   :nodes ["n1" "n2" "n3" "n4" "n5"]
   ;; SSH configuration
   :remote c/ssh
   :username "root"
   :private-key-path "/root/.ssh/id_rsa"
   :strict-host-key-checking false
   :ssh-opts ["-o" "StrictHostKeyChecking=no"
              "-o" "UserKnownHostsFile=/dev/null"
              "-o" "GlobalKnownHostsFile=/dev/null"
              "-o" "LogLevel=ERROR"]
   ;; Fixed generator - use cycle for infinite operations
   :generator (->> (cycle [;; Create more read operations (70% reads)
                           {:type :invoke :f :read}
                           {:type :invoke :f :read}
                           {:type :invoke :f :read}
                           {:type :invoke :f :read}
                           {:type :invoke :f :read}
                           {:type :invoke :f :read}
                           {:type :invoke :f :read}
                           ;; 30% writes
                           {:type :invoke :f :write :value (rand-int 1000)}
                           {:type :invoke :f :write :value (rand-int 1000)}
                           {:type :invoke :f :write :value (rand-int 1000)}])
                   (gen/stagger 1/50)  ; 50 ops per second per thread
                   (gen/time-limit 180))  ; 3 minutes
   :checker (checker/compose
             {:perf (checker/perf)
              :timeline (timeline/html)
              :linear (checker/linearizable {:model (model/register)})
              :stats (checker/stats)})})

(defn run-simple-test []
  "Run the simple 30-second test"
  (info "🚀 Starting simple Redis test for 30 seconds...")
  (info "📊 Expected ~900 operations (3 threads × 10 ops/sec × 30 sec)")
  (jepsen/run! (simple-test)))

(defn run-intensive-test []
  "Run the intensive 3-minute test"
  (info "🚀 Starting intensive Redis test for 3 minutes...")
  (info "📊 Expected ~135,000 operations (15 threads × 50 ops/sec × 180 sec)")
  (jepsen/run! (intensive-test)))

(defn -main [& args]
  ;; Set up SSH configuration globally with explicit host key bypass
  (c/with-ssh {:username "root"
               :private-key-path "/root/.ssh/id_rsa"
               :strict-host-key-checking false
               :ssh-opts ["-o" "StrictHostKeyChecking=no"
                          "-o" "UserKnownHostsFile=/dev/null"
                          "-o" "GlobalKnownHostsFile=/dev/null"
                          "-o" "LogLevel=ERROR"]}
    ;; Optional: Run a sanity check first
    (info "✅ Verifying SSH connectivity before starting test")
    (doseq [node ["n1" "n2" "n3" "n4" "n5"]]
      (try
        (c/on node
              (info "Uptime from" node ": " (c/exec :uptime)))
        (catch Exception e
          (error "Failed to connect to" node ":" (.getMessage e)))))

    ;; Choose which test to run based on command line argument
    (let [test-type (first args)]
      (case test-type
        "simple" (run-simple-test)
        "intensive" (run-intensive-test)
        ;; Default: run both tests
        (do
          (info "🎯 Running both tests (use 'simple' or 'intensive' to run individual tests)")
          (run-simple-test)
          (Thread/sleep 5000)  ; Wait 5 seconds between tests
          (run-intensive-test))))))