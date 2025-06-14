(ns jepsen.redis-sentinel.core
  (:gen-class)
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [client :as client]
                    [control :as c]
                    [db :as db]
                    [generator :as gen]
                    [independent :as independent]
                    [nemesis :as nemesis]
                    [tests :as tests]
                    [util :as util]
                    [core :as jepsen]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [knossos.model :as model]
            [taoensso.carmine :as car :refer [wcar]]
            [slingshot.slingshot :refer [try+]]))

;; Utility function for timing
(defn nano-time []
  "Get current time in nanoseconds"
  (System/nanoTime))

(defn redis-sentinel-db []
  (reify db/DB
    (setup! [_ test node]
      (info "Setting up Redis Sentinel on" node)
      (try
        (c/exec :redis-cli :ping)
        (info "Redis responding on" node)
        (catch Exception e
          (warn "Redis setup issue on" node ":" (.getMessage e)))))
    
    (teardown! [_ test node]
      (info "Tearing down Redis on" node))))

(defn redis-sentinel-client []
  (let [conn (atom nil)]
    (reify client/Client
      (open! [this test node]
        (info "Opening Redis connection to" node)
        (reset! conn {:pool {} :spec {:host node :port 6379}})
        this)
      
      (setup! [this test]
        (info "Setting up Redis client"))
      
      (invoke! [this test operation]
        (let [start-time (nano-time)]
          (try
            (case (:f operation)
              :read
              (let [value (wcar @conn (car/get (:key operation)))]
                (assoc operation
                       :type :ok
                       :value value
                       :latency (- (nano-time) start-time)))
              
              :write
              (do
                (wcar @conn (car/set (:key operation) (:value operation)))
                (assoc operation
                       :type :ok
                       :latency (- (nano-time) start-time)))
              
              :cas
              (let [result (wcar @conn
                                 (car/watch (:key operation))
                                 (car/multi)
                                 (when (= (car/get (:key operation)) (:old-value operation))
                                   (car/set (:key operation) (:new-value operation)))
                                 (car/exec))]
                (assoc operation
                       :type :ok
                       :value (if (seq result) :ok :fail)
                       :latency (- (nano-time) start-time)))
              
              (assoc operation :type :fail :error "Unknown operation"))
            
            (catch Exception e
              (assoc operation
                     :type :fail
                     :error (.getMessage e)
                     :latency (- (nano-time) start-time))))))
      
      (teardown! [this test]
        (info "Tearing down Redis client"))
      
      (close! [this test]
        (info "Closing Redis connection")))))

;; Pure Jepsen Nemesis Configurations
(defn redis-nemesis
  "Redis-specific nemesis using pure Jepsen components"
  []
  (nemesis/compose
    {{:start-partition :stop-partition} 
     (nemesis/partition-random-halves)
     
     {:start-primary-isolation :stop-primary-isolation}
     (nemesis/partition-random-node)
     
     {:start-bridge-partition :stop-bridge-partition}
     (nemesis/partition-majorities-ring)
     
     {:start-kill :stop-kill}
     (nemesis/node-start-stopper
       identity
       (fn [test node] 
         (c/su (c/exec :systemctl :start :redis-server)))
       (fn [test node] 
         (c/su (c/exec :systemctl :stop :redis-server))))}))

(defn sentinel-aware-nemesis
  "Nemesis that understands Redis Sentinel topology"
  []
  (nemesis/compose
    {{:start-sentinel-partition :stop-sentinel-partition}
     (nemesis/partition-random-halves)
     
     {:start-primary-kill :stop-primary-kill}
     (nemesis/node-start-stopper
       (fn [test] 
         ;; Find current primary - simplified to first node
         (first (:nodes test)))
       (fn [test node] 
         (c/su (c/exec :systemctl :start :redis-server)))
       (fn [test node] 
         (c/su (c/exec :systemctl :stop :redis-server))))}))

;; Pure Jepsen Workload Generators
(defn register-workload
  "Pure Jepsen register workload"
  [opts]
  (->> (independent/concurrent-generator
         10  ; 10 concurrent registers
         (range)
         (fn [k]
           (->> (gen/mix [{:type :invoke :f :read :key k}
                         {:type :invoke :f :write :key k 
                          :value (rand-int 1000)}])
                (gen/limit 100))))
       (gen/stagger 1/10)))

(defn cas-workload
  "Pure Jepsen compare-and-swap workload"
  [opts]
  (->> (independent/concurrent-generator
         5   ; 5 concurrent registers
         (range)
         (fn [k]
           (gen/reserve 5 
             (->> (gen/mix [{:type :invoke :f :read :key k}
                           {:type :invoke :f :write :key k 
                            :value (rand-int 100)}
                           {:type :invoke :f :cas :key k
                            :old-value 0 :new-value 1}])
                  (gen/limit 200)))))
       (gen/stagger 1/20)))

(defn mixed-workload
  "Mixed workload for comprehensive testing"
  [opts]
  (gen/phases
    ;; Phase 1: Initial data population
    (->> (gen/each-thread (map (fn [k] {:type :invoke :f :write 
                                       :key k :value (rand-int 100)}) 
                              (range 50)))
         (gen/time-limit 30))
    
    ;; Phase 2: Mixed operations with faults
    (->> (gen/mix [{:type :invoke :f :read :key (rand-int 50)}
                  {:type :invoke :f :write :key (rand-int 50) 
                   :value (rand-int 1000)}
                  {:type :invoke :f :cas :key (rand-int 50)
                   :old-value (rand-int 100) :new-value (rand-int 100)}])
         (gen/stagger 1/10)
         (gen/nemesis 
           (cycle [(gen/sleep 30)
                  {:type :info :f :start-partition}
                  (gen/sleep 20)
                  {:type :info :f :stop-partition}
                  (gen/sleep 10)]))
         (gen/time-limit 180))))

;; Pure Jepsen Test Configurations
(defn linearizability-test
  "Basic linearizability test using pure Jepsen"
  [opts]
  (merge tests/noop-test
         opts
         {:name "redis-sentinel-linearizability"
          :os debian/os
          :db (redis-sentinel-db)
          :client (redis-sentinel-client)
          :nemesis (redis-nemesis)
          :concurrency 10
          :generator (register-workload opts)
          :checker (checker/compose
                     {:perf (checker/perf)
                      :timeline (timeline/html)
                      :linear (checker/linearizable {:model (model/register)})
                      :stats (checker/stats)})}))

(defn split-brain-test
  "Split-brain test using pure Jepsen partition nemesis"
  [opts]
  (merge tests/noop-test
         opts
         {:name "redis-sentinel-split-brain"
          :os debian/os
          :db (redis-sentinel-db)
          :client (redis-sentinel-client)
          :nemesis (sentinel-aware-nemesis)
          :concurrency 5
          :generator (gen/phases
                       ;; Initial setup
                       (->> (gen/each-thread (map (fn [i] {:type :invoke :f :write 
                                                          :key i :value i}) 
                                                 (range 100)))
                            (gen/time-limit 30))
                       
                       ;; Split-brain scenario
                       (->> (gen/mix [{:type :invoke :f :write 
                                      :key (rand-int 100) 
                                      :value (+ 1000 (rand-int 1000))}
                                     {:type :invoke :f :read 
                                      :key (rand-int 100)}])
                            (gen/stagger 1/5)
                            (gen/nemesis
                              (gen/repeat [(gen/once {:type :info :f :start-sentinel-partition})
                                            (gen/sleep 60)
                                            (gen/once {:type :info :f :stop-sentinel-partition})]))
                            (gen/time-limit 120)))
          :checker (checker/compose
                     {:perf (checker/perf)
                      :timeline (timeline/html)
                      :linear (checker/linearizable {:model (model/register)})
                      :stats (checker/stats)})}))

(defn network-partition-test
  "Network partition test using pure Jepsen"
  [opts]
  (merge tests/noop-test
         opts
         {:name "redis-sentinel-network-partition"
          :os debian/os
          :db (redis-sentinel-db)
          :client (redis-sentinel-client)
          :nemesis (nemesis/partition-random-halves)
          :concurrency 8
          :generator (gen/phases
                       (->> (cas-workload opts)
                            (gen/nemesis
                              (cycle [(gen/sleep 30)
                                     {:type :info :f :start}
                                     (gen/sleep 20)
                                     {:type :info :f :stop}
                                     (gen/sleep 10)]))
                            (gen/time-limit 300)))
          :checker (checker/compose
                     {:perf (checker/perf)
                      :timeline (timeline/html)
                      :linear (checker/linearizable {:model (model/cas-register)})
                      :stats (checker/stats)})}))

(defn process-kill-test
  "Process kill test using pure Jepsen"
  [opts]
  (merge tests/noop-test
         opts
         {:name "redis-sentinel-process-kill"
          :os debian/os
          :db (redis-sentinel-db)
          :client (redis-sentinel-client)
          :nemesis (nemesis/node-start-stopper
                     identity
                     (fn [test node] 
                       (c/su (c/exec :systemctl :start :redis-server)))
                     (fn [test node] 
                       (c/su (c/exec :systemctl :stop :redis-server))))
          :concurrency 6
          :generator (gen/phases
                       (->> (mixed-workload opts)
                            (gen/nemesis
                              (cycle [(gen/sleep 45)
                                     {:type :info :f :start}
                                     (gen/sleep 15)
                                     {:type :info :f :stop}
                                     (gen/sleep 30)]))
                            (gen/time-limit 240)))
          :checker (checker/compose
                     {:perf (checker/perf)
                      :timeline (timeline/html)
                      :linear (checker/linearizable {:model (model/register)})
                      :stats (checker/stats)})}))

(defn comprehensive-test
  "Comprehensive test combining multiple nemesis types"
  [opts]
  (merge tests/noop-test
         opts
         {:name "redis-sentinel-comprehensive"
          :os debian/os
          :db (redis-sentinel-db)
          :client (redis-sentinel-client)
          :nemesis (nemesis/compose
                     {{:start-partition :stop-partition} 
                      (nemesis/partition-random-halves)
                      {:start-kill :stop-kill}
                      (nemesis/node-start-stopper
                        identity
                        (fn [test node] 
                          (c/su (c/exec :systemctl :start :redis-server)))
                        (fn [test node] 
                          (c/su (c/exec :systemctl :stop :redis-server))))})
          :concurrency 12
          :generator (gen/phases
                       ;; Phase 1: Basic operations
                       (->> (register-workload opts)
                            (gen/time-limit 60))
                       
                       ;; Phase 2: Partition testing
                       (->> (cas-workload opts)
                            (gen/nemesis
                              (cycle [(gen/sleep 20)
                                     {:type :info :f :start-partition}
                                     (gen/sleep 30)
                                     {:type :info :f :stop-partition}
                                     (gen/sleep 15)]))
                            (gen/time-limit 180))
                       
                       ;; Phase 3: Process kill testing
                       (->> (mixed-workload opts)
                            (gen/nemesis
                              (cycle [(gen/sleep 25)
                                     {:type :info :f :start-kill}
                                     (gen/sleep 20)
                                     {:type :info :f :stop-kill}
                                     (gen/sleep 35)]))
                            (gen/time-limit 240)))
          :checker (checker/compose
                     {:perf (checker/perf)
                      :timeline (timeline/html)
                      :linear (checker/linearizable {:model (model/register)})
                      :stats (checker/stats)})}))

;; Test suite definitions
(def test-suite
  {:linearizability    linearizability-test
   :split-brain        split-brain-test
   :network-partition  network-partition-test
   :process-kill       process-kill-test
   :comprehensive      comprehensive-test})

;; Main CLI interface with SSH configuration
(defn -main
  "Main entry point using pure Jepsen CLI with SSH credentials"
  [& args]
  ;; Set up SSH credentials that work with our Docker setup
  (c/with-ssh {:username "root"
               :private-key-path "/root/.ssh/id_rsa"
               :strict-host-key-checking false}
    (info "SSH credentials configured for Jepsen")
    (cli/run!
     (merge (cli/single-test-cmd {:test-fn (fn [opts]
                                             (let [test-name (keyword (get opts :test-name "linearizability"))
                                                   test-fn (get test-suite test-name linearizability-test)]
                                               (test-fn opts)))
                                  :opt-spec [[nil "--test-name NAME" "Test to run"
                                              :default "linearizability"
                                              :validate [#(contains? test-suite (keyword %))
                                                         "Must be a valid test name"]]]})
            (cli/serve-cmd)
            {:opt-spec [[nil "--nodes HOSTS" "Comma-separated list of hosts"
                         :default ["n1" "n2" "n3" "n4" "n5"]
                         :parse-fn #(str/split % #",")]
                        [nil "--username USER" "SSH username"
                         :default "root"]
                        [nil "--private-key-path PATH" "SSH private key path"
                         :default "/root/.ssh/id_rsa"]
                        [nil "--strict-host-key-checking" "Enable strict host key checking"
                         :default false
                         :parse-fn #(Boolean/parseBoolean %)]
                        [nil "--time-limit SECONDS" "Test time limit"
                         :default 300
                         :parse-fn #(Integer/parseInt %)]]})
     args)))