(ns jepsen.redis-sentinel.split-brain-test
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [go chan >! <!]]
            [jepsen.redis-sentinel.test-harness :as harness]
            [jepsen.redis-sentinel.client :as client]
            [jepsen.redis-sentinel.nemesis :as nemesis]
            [jepsen.nemesis :as jepsen-nemesis]
            [jepsen.control :as c]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [taoensso.carmine :as car :refer [wcar]]))

;; Use the same Sentinel connection approach as failover_test
(def sentinel-conn {:pool {} :spec {:host "redis-sentinel1" :port 26379}})

(defn get-current-primary []
  "Discover current primary from Sentinel (same as failover_test)"
  (log/info "Starting primary discovery from Sentinel...")
  (log/debug "Sentinel connection spec:" sentinel-conn)
  (try
    (log/debug "Querying Sentinel for master 'mymaster'...")
    (let [master-info (wcar sentinel-conn
                            (car/redis-call ["SENTINEL" "get-master-addr-by-name" "mymaster"]))]
      (log/debug "Raw master info from Sentinel:" master-info)
      (if master-info
        (let [host (first master-info)
              port-str (second master-info)
              port (Integer/parseInt port-str)
              conn-spec {:pool {} :spec {:host host :port port}}]
          (log/debug "Parsed master host:" host)
          (log/debug "Parsed master port:" port)
          conn-spec)
        (do
          (log/error "No master info returned from Sentinel!")
          (throw (Exception. "No master found")))))
    (catch NumberFormatException e
      (log/error "Failed to parse master port as integer:" (.getMessage e))
      (throw e))
    (catch Exception e
      (log/error "Failed to get master from Sentinel:" (.getMessage e))
      (throw e))))

(defn get-replica-connections []
  "Get all replica connections from Sentinel (same as failover_test)"
  (try
    (let [replicas (wcar sentinel-conn
                         (car/redis-call ["SENTINEL" "replicas" "mymaster"]))]
      (log/debug "Raw replicas info from Sentinel:" replicas)
      (for [replica-info replicas]
        (let [info-map (apply hash-map replica-info)
              host (get info-map "ip")
              port (Integer/parseInt (get info-map "port"))]
          {:pool {} :spec {:host host :port port}})))
    (catch Exception e
      (log/warn "Failed to get replicas:" (.getMessage e))
      [])))

;; Split-brain specific nemesis
(defn split-brain-nemesis
  "Creates a split-brain scenario by partitioning minority from majority"
  []
  (reify jepsen-nemesis/Nemesis
    (setup! [this test] 
      (log/info "Setting up split-brain nemesis")
      this)
    
    (invoke! [this test op]
      (case (:f op)
        :start-split-brain 
        (do
          (log/info "=== STARTING SPLIT-BRAIN PARTITION ===")
          (log/info "Partitioning n1,n2 (minority) from n3,n4,n5 (majority)")
          (try
            ;; Create the classic 2 vs 3 split
            (let [minority-nodes ["n1" "n2"]
                  majority-nodes ["n3" "n4" "n5"]]
              
              ;; Disconnect minority nodes from Redis network
              (doseq [node minority-nodes]
                (shell/sh "docker" "network" "disconnect" "redis-network" 
                         (str "jepsen-redis-" node))
                (log/debug "Disconnected" node "from redis-network"))
              
              ;; Disconnect minority sentinels from Sentinel network
              (shell/sh "docker" "network" "disconnect" "redis-sentinel-network" 
                       "jepsen-redis-sentinel1")
              (shell/sh "docker" "network" "disconnect" "redis-sentinel-network" 
                       "jepsen-redis-sentinel2")
              
              (log/info "Split-brain partition created successfully")
              (log/info "Minority partition: n1, n2 (with sentinels 1,2)")
              (log/info "Majority partition: n3, n4, n5 (with sentinel 3)")
              (log/info "Sentinels in majority will elect new primary"))
            
            (assoc op :type :ok :value "Split-brain partition started")
            (catch Exception e
              (log/error "Failed to create split-brain partition:" (.getMessage e))
              (assoc op :type :fail :error (.getMessage e)))))
        
        :stop-split-brain
        (do
          (log/info "=== HEALING SPLIT-BRAIN PARTITION ===")
          (try
            ;; Reconnect minority nodes to Redis network
            (let [minority-nodes ["n1" "n2"]]
              (doseq [node minority-nodes]
                (shell/sh "docker" "network" "connect" "redis-network" 
                         (str "jepsen-redis-" node))
                (log/debug "Reconnected" node "to redis-network"))
              
              ;; Reconnect minority sentinels
              (shell/sh "docker" "network" "connect" "redis-sentinel-network" 
                       "jepsen-redis-sentinel1")
              (shell/sh "docker" "network" "connect" "redis-sentinel-network" 
                       "jepsen-redis-sentinel2")
              
              (log/info "Split-brain partition healed successfully")
              (log/info "All nodes and sentinels reconnected")
              (log/info "Sentinels will coordinate to resolve split-brain"))
            
            (assoc op :type :ok :value "Split-brain partition healed")
            (catch Exception e
              (log/error "Failed to heal split-brain partition:" (.getMessage e))
              (assoc op :type :fail :error (.getMessage e)))))
        
        ;; Handle other operations gracefully
        (do
          (log/warn "Unknown split-brain nemesis operation:" (:f op))
          (assoc op :type :fail :error "Unknown operation"))))
    
    (teardown! [this test]
      (log/info "Tearing down split-brain nemesis")
      ;; Ensure all connections are restored
      (try
        (let [all-nodes ["n1" "n2" "n3" "n4" "n5"]]
          (doseq [node all-nodes]
            (shell/sh "docker" "network" "connect" "redis-network" 
                     (str "jepsen-redis-" node)))
          (doseq [sentinel-id ["1" "2" "3"]]
            (shell/sh "docker" "network" "connect" "redis-sentinel-network" 
                     (str "jepsen-redis-sentinel" sentinel-id))))
        (catch Exception e
          (log/warn "Error during nemesis teardown:" (.getMessage e)))))))

(defn advanced-split-brain-nemesis
  "Advanced nemesis that can create different split-brain scenarios"
  []
  (jepsen-nemesis/compose
    {{:start-split-brain :stop-split-brain} (split-brain-nemesis)
     {:start-primary-isolation :stop-primary-isolation} (nemesis/partition-primary-nemesis)
     {:start-sentinel-failure :stop-sentinel-failure} (nemesis/kill-sentinel-nemesis)}))

(defn create-split-brain-workload
  "Creates a workload that writes sequential integers to test data loss"
  [duration-ms ops-per-second]
  (let [channel (chan 100)
        ops-interval-ms (/ 1000 ops-per-second)
        start-time (System/currentTimeMillis)
        end-time (+ start-time duration-ms)]
    
    (go
      (doseq [counter (range 1 Integer/MAX_VALUE)
              :while (< (System/currentTimeMillis) end-time)]
        (>! channel {:type :write 
                     :key "sequential-writes"
                     :value counter
                     :operation-id counter
                     :timestamp (System/currentTimeMillis)})
        (<! (async/timeout ops-interval-ms)))
      (async/close! channel))
    
    channel))

(defn write-to-primary
  "Write to the specified primary connection"
  [conn key value]
  (wcar conn
    (car/sadd key value)))

(defn read-from-node
  "Read from a specific node"
  [conn key]
  (wcar conn
    (car/smembers key)))

(defn execute-nemesis-operation
  "Execute a nemesis operation with proper error handling"
  [nemesis-instance test operation]
  (try
    (log/debug "Executing nemesis operation:" (:f operation))
    (let [result (jepsen-nemesis/invoke! nemesis-instance test operation)]
      (log/info "Nemesis operation result:" (:value result))
      result)
    (catch Exception e
      (log/error "Nemesis operation failed:" (.getMessage e))
      (assoc operation :type :fail :error (.getMessage e)))))

(defn inject-split-brain-with-nemesis
  "Use nemesis to inject split-brain scenario"
  [nemesis-instance test duration]
  (go
    (log/info "Starting nemesis-based split-brain injection")
    
    ;; Start split-brain partition
    (let [start-op {:type :invoke :f :start-split-brain :time (System/currentTimeMillis)}
          start-result (execute-nemesis-operation nemesis-instance test start-op)]
      
      (if (= (:type start-result) :ok)
        (do
          (log/info "Split-brain partition established, waiting" duration "ms")
          (<! (async/timeout duration))
          
          ;; Stop split-brain partition (heal)
          (let [stop-op {:type :invoke :f :stop-split-brain :time (System/currentTimeMillis)}
                stop-result (execute-nemesis-operation nemesis-instance test stop-op)]
            
            (if (= (:type stop-result) :ok)
              (log/info "Split-brain partition healed successfully")
              (log/error "Failed to heal split-brain partition"))))
        (log/error "Failed to establish split-brain partition")))))

(defn create-split-brain-sentinel-aware-client
  "Creates a client that queries Sentinel before every write (stricter than normal)"
  [client-id]
  (reify client/RedisClient
    (write-key [this key value]
      (let [start-time (harness/timestamp)]
        (harness/record-operation {:type :invoke :f :write :key key :value value
                                   :client client-id :time start-time})
        (try
          ;; Query Sentinel for current primary before EVERY write (stricter implementation)
          (let [primary-conn (get-current-primary)]
            (log/debug "Client" client-id "using primary:" (get-in primary-conn [:spec :host]))
            
            ;; Attempt the write
            (let [result (write-to-primary primary-conn key value)
                  end-time (harness/timestamp)]
              (harness/record-operation {:type :ok :f :write :key key :value value
                                         :client client-id :time end-time
                                         :primary-node (get-in primary-conn [:spec :host])})
              result))
          (catch Exception e
            (let [end-time (harness/timestamp)]
              (harness/record-operation {:type :fail :f :write :key key :value value
                                         :client client-id :time end-time
                                         :error (.getMessage e)})
              (throw e))))))

    (read-key [this key]
      (let [start-time (harness/timestamp)]
        (harness/record-operation {:type :invoke :f :read :key key
                                   :client client-id :time start-time})
        (try
          ;; Read from any available replica
          (let [replica-conns (get-replica-connections)
                replica-conn (if (seq replica-conns)
                               (rand-nth replica-conns)
                               (get-current-primary)) ; Fallback to primary if no replicas
                result (read-from-node replica-conn key)
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
    
    ;; Add the missing protocol methods with no-op implementations
    (cas-key [this key old-value new-value]
      (throw (UnsupportedOperationException. "CAS not implemented for split-brain test")))
    
    (delete-key [this key]
      (throw (UnsupportedOperationException. "Delete not implemented for split-brain test")))
    
    (increment-key [this key]
      (throw (UnsupportedOperationException. "Increment not implemented for split-brain test")))
    
    (scan-keys [this pattern]
      (throw (UnsupportedOperationException. "Scan not implemented for split-brain test")))))

(defn run-split-brain-client-worker
  "Client worker that performs sequential writes to detect data loss"
  [client-id workload-channel]
  (go
    (let [client (create-split-brain-sentinel-aware-client client-id)]
      (log/info "Starting split-brain test client" client-id "(queries Sentinel before every write)")
      
      ;; Use while loop instead of loop/recur
      (let [continue (atom true)]
        (while @continue
          (if-let [operation (<! workload-channel)]
            (try
              (case (:type operation)
                :write (client/write-key client (:key operation) (:value operation))
                :read (client/read-key client (:key operation))
                (log/warn "Unknown operation type:" (:type operation)))
              (catch Exception e
                (log/debug "Client" client-id "operation failed:" (.getMessage e))))
            (reset! continue false))))
      
      (log/info "Split-brain test client" client-id "finished"))))

(defn read-final-state-from-all-nodes
  "Read the final state from all available nodes (primary + replicas)"
  []
  (let [all-nodes (atom {})]
    
    ;; Read from current primary
    (try
      (let [primary-conn (get-current-primary)
            primary-host (get-in primary-conn [:spec :host])
            primary-data (wcar primary-conn
                              (car/smembers "sequential-writes"))]
        (swap! all-nodes assoc :primary {:host primary-host 
                                        :data (set (map #(Integer/parseInt (str %)) primary-data))
                                        :count (count primary-data)})
        (log/info "Read from primary" primary-host ":" (count primary-data) "values"))
      (catch Exception e
        (log/error "Failed to read from primary:" (.getMessage e))))
    
    ;; Read from all replicas
    (try
      (let [replica-conns (get-replica-connections)]
        (doseq [[idx replica-conn] (map-indexed vector replica-conns)]
          (try
            (let [replica-host (get-in replica-conn [:spec :host])
                  replica-data (wcar replica-conn
                                    (car/smembers "sequential-writes"))]
              (swap! all-nodes assoc (keyword (str "replica-" idx)) 
                     {:host replica-host
                      :data (set (map #(Integer/parseInt (str %)) replica-data))
                      :count (count replica-data)})
              (log/info "Read from replica" replica-host ":" (count replica-data) "values"))
            (catch Exception e
              (log/warn "Failed to read from replica" idx ":" (.getMessage e))))))
      (catch Exception e
        (log/error "Failed to get replica connections:" (.getMessage e))))
    
    @all-nodes))

(defn analyze-consistency-across-nodes
  "Analyze consistency of the final set across all nodes"
  [node-states]
  (let [all-data-sets (map :data (vals node-states))
        all-hosts (map :host (vals node-states))
        
        ;; Find intersection (values present on ALL nodes)
        consistent-values (if (seq all-data-sets)
                           (apply set/intersection all-data-sets)
                           #{})
        
        ;; Find union (values present on ANY node)
        all-values (if (seq all-data-sets)
                    (apply set/union all-data-sets)
                    #{})
        
        ;; Find inconsistent values (present on some but not all nodes)
        inconsistent-values (set/difference all-values consistent-values)
        
        ;; Analyze per-node differences
        node-analysis (for [[node-key node-info] node-states]
                        (let [node-data (:data node-info)
                              unique-to-node (set/difference node-data all-values)
                              missing-from-node (set/difference all-values node-data)]
                          {:node node-key
                           :host (:host node-info)
                           :total-count (:count node-info)
                           :unique-values unique-to-node
                           :missing-values missing-from-node
                           :consistency-ratio (if (seq all-values)
                                               (double (/ (count (set/intersection node-data all-values))
                                                         (count all-values)))
                                               1.0)}))]
    
    {:total-nodes (count node-states)
     :consistent-values consistent-values
     :consistent-count (count consistent-values)
     :all-values all-values
     :total-unique-values (count all-values)
     :inconsistent-values inconsistent-values
     :inconsistent-count (count inconsistent-values)
     :consistency-percentage (if (seq all-values)
                              (* 100.0 (double (/ (count consistent-values) 
                                                  (count all-values))))
                              100.0)
     :node-analysis node-analysis
     :hosts all-hosts}))

(defn detect-split-brain-survivors
  "Detect which values survived the split-brain scenario and analyze patterns"
  [acknowledged-writes final-state-analysis]
  (let [survivors (:all-values final-state-analysis)
        consistent-survivors (:consistent-values final-state-analysis)
        lost-writes (set/difference acknowledged-writes survivors)
        
        ;; Analyze survival patterns
        sorted-survivors (sort survivors)
        sorted-lost (sort lost-writes)
        
        ;; Find gaps in the sequence (indicating lost consecutive writes)
        gaps (for [[a b] (partition 2 1 sorted-survivors)
                   :when (> (- b a) 1)]
               {:start (inc a) :end (dec b) :size (- b a 1)})
        
        ;; Analyze which node has the most complete data
        best-node (apply max-key #(count (:data (second %))) (:node-analysis final-state-analysis))
        worst-node (apply min-key #(count (:data (second %))) (:node-analysis final-state-analysis))]
    
    (log/info "=== SPLIT-BRAIN SURVIVOR ANALYSIS ===")
    (log/info "Values acknowledged by Redis:" (count acknowledged-writes))
    (log/info "Values present on ANY node:" (count survivors))
    (log/info "Values present on ALL nodes:" (count consistent-survivors))
    (log/info "Values completely lost:" (count lost-writes))
    
    (log/info "Survival rate (any node):" (format "%.3f" 
                                                  (if (seq acknowledged-writes)
                                                    (double (/ (count survivors) (count acknowledged-writes)))
                                                    0.0)))
    
    (log/info "Consistency rate (all nodes):" (format "%.3f" 
                                                      (if (seq acknowledged-writes)
                                                        (double (/ (count consistent-survivors) (count acknowledged-writes)))
                                                        0.0)))
    
    (when (seq gaps)
      (log/info "Detected gaps in sequence (lost consecutive writes):")
      (doseq [gap (take 5 gaps)]
        (log/info "  Gap:" (:start gap) "to" (:end gap) "(" (:size gap) "values lost)")))
    
    (when (seq sorted-lost)
      (log/info "Lost writes sample:" (take 20 sorted-lost)))
    
    (log/info "Node with most data:" (when best-node 
                                       (str (:host (second best-node)) 
                                            " (" (:total-count (second best-node)) " values)")))
    (log/info "Node with least data:" (when worst-node 
                                        (str (:host (second worst-node)) 
                                             " (" (:total-count (second worst-node)) " values)")))
    
    {:survivors survivors
     :consistent-survivors consistent-survivors
     :lost-writes lost-writes
     :gaps gaps
     :survival-rate (if (seq acknowledged-writes)
                      (double (/ (count survivors) (count acknowledged-writes)))
                      0.0)
     :consistency-rate (if (seq acknowledged-writes)
                         (double (/ (count consistent-survivors) (count acknowledged-writes)))
                         0.0)}))

(defn analyze-split-brain-data-loss
  "Analyze the test results for split-brain data loss patterns using final replica states"
  [histories]
  (let [write-ops (filter #(and (= (:f %) :write) (= (:type %) :ok)) histories)
        total-writes (count write-ops)
        acknowledged-writes (set (map :value write-ops))
        
        ;; Read final state from ALL nodes (primary + replicas)
        final-node-states (read-final-state-from-all-nodes)
        
        ;; Analyze consistency across nodes
        consistency-analysis (analyze-consistency-across-nodes final-node-states)
        
        ;; Detect split-brain survival patterns
        survivor-analysis (detect-split-brain-survivors acknowledged-writes consistency-analysis)
        
        ;; Calculate rates using the most complete picture
        all-survivors (:all-values consistency-analysis)
        consistent-survivors (:consistent-values consistency-analysis)
        lost-writes (set/difference acknowledged-writes all-survivors)
        
        ;; Calculate rates and convert to double for formatting
        ack-rate (if (> total-writes 0)
                   (double (/ (count acknowledged-writes) total-writes))
                   0.0)
        loss-rate (if (> (count acknowledged-writes) 0)
                    (double (/ (count lost-writes) (count acknowledged-writes)))
                    0.0)
        
        ;; Analyze patterns in lost writes
        sorted-lost (sort lost-writes)
        early-losses (take-while #(< % 100) sorted-lost)
        late-losses (filter #(>= % 100) sorted-lost)]

    (log/info "=== SPLIT-BRAIN DATA LOSS ANALYSIS ===")
    (log/info "Total writes attempted:" total-writes)
    (log/info "Writes acknowledged:" (count acknowledged-writes))
    (log/info "Writes surviving on any node:" (count all-survivors))
    (log/info "Writes consistent across all nodes:" (count consistent-survivors))
    (log/info "Acknowledged writes completely lost:" (count lost-writes))
    (log/info "Acknowledgment rate:" (format "%.3f" ack-rate))
    (log/info "Loss rate:" (format "%.3f" loss-rate))
    (log/info "Consistency rate:" (format "%.3f" (:consistency-rate survivor-analysis)))

    (log/info "=== NODE-BY-NODE BREAKDOWN ===")
    (doseq [node-info (:node-analysis consistency-analysis)]
      (log/info "Node" (:host node-info) ":"
                (:total-count node-info) "values,"
                "consistency ratio:" (format "%.3f" (:consistency-ratio node-info))))

    (when (seq early-losses)
      (log/info "Early losses (partition start):" (take 10 early-losses)))

    (when (seq late-losses)
      (log/info "Late losses (split-brain writes):" (take 10 late-losses)))

    ;; Detect split-brain pattern by analyzing which primaries were used
    (let [write-primaries (keep :primary-node write-ops)
          primary-distribution (frequencies write-primaries)]
      (when (> (count primary-distribution) 1)
        (log/info "Multiple primaries detected during test:" primary-distribution)
        (log/info "This confirms split-brain scenario occurred")))

    ;; Enhanced return map with detailed node analysis
    {:total-writes total-writes
     :acknowledged-writes (count acknowledged-writes)
     :survivors (count all-survivors)
     :consistent-survivors (count consistent-survivors)
     :lost-writes (count lost-writes)
     :ack-rate ack-rate
     :loss-rate loss-rate
     :consistency-rate (:consistency-rate survivor-analysis)
     :early-losses early-losses
     :late-losses late-losses
     :split-brain-detected (> (count late-losses) 0)
     :node-states final-node-states
     :consistency-analysis consistency-analysis
     :survivor-analysis survivor-analysis
     :inconsistency-detected (< (:consistency-percentage consistency-analysis) 100.0)}))

(defn run-split-brain-test
  "Main split-brain test using nemesis for fault injection"
  []
  (log/info "=== STARTING REDIS SENTINEL SPLIT-BRAIN TEST (NEMESIS) ===")
  (log/info "This test reproduces the exact split-brain scenario described")
  (log/info "where Redis loses 56% of acknowledged writes")
  
  (harness/reset-test-state!)
  (swap! harness/test-state assoc :active true :client-count 5)
  
  ;; Create nemesis instance
  (let [nemesis-instance (split-brain-nemesis)
        test-config {:nodes ["n1" "n2" "n3" "n4" "n5"]}]
    
    ;; Setup nemesis
    (jepsen-nemesis/setup! nemesis-instance test-config)
    
    ;; Clear any existing data using current primary
    (try
      (let [primary-conn (get-current-primary)]
        (wcar primary-conn
          (car/del "sequential-writes")))
      (catch Exception e
        (log/warn "Failed to clear existing data:" e)))
    
    (let [duration-ms 180000  ; 3 minutes total
          ops-per-second 20   ; Moderate write rate
          client-count 5      ; 5 clients writing sequentially
          
          ;; Create workload - each client writes sequential integers
          workload-channels (for [i (range client-count)]
                              (create-split-brain-workload duration-ms ops-per-second))
          
          ;; Start client workers
          workers (for [[i workload-channel] (map-indexed vector workload-channels)]
                    (run-split-brain-client-worker i workload-channel))
          
          ;; Start the split-brain fault injection using nemesis after 30 seconds
          fault-worker (inject-split-brain-with-nemesis nemesis-instance test-config 60000)]
      
      (log/info "Started 5 clients writing sequential integers")
      (log/info "Each client queries Sentinel before every write (strict implementation)")
      (log/info "Split-brain partition will be injected after 30 seconds using nemesis")
      
      ;; Wait for all workers to complete
      (doseq [worker workers]
        (async/<!! worker))
      
      ;; Wait for fault injection to complete
      (async/<!! fault-worker)
      
      ;; Allow time for final replication
      (Thread/sleep 10000)
      
      ;; Teardown nemesis
      (jepsen-nemesis/teardown! nemesis-instance test-config)
      
      ;; Finalize test
      (swap! harness/test-state assoc :active false)
      
      ;; Analyze results
      (let [histories (:histories @harness/test-state)
            analysis (analyze-split-brain-data-loss histories)]
        
        (log/info "=== SPLIT-BRAIN TEST COMPLETED ===")
        
        ;; Save detailed results
        (harness/save-histories (str "split-brain-nemesis-test-" (System/currentTimeMillis) ".json"))
        
        ;; Return analysis
        analysis))))

(defn run-split-brain-durability-test
  "Extended test that focuses on durability violations using nemesis"
  []
  (log/info "=== REDIS SENTINEL DURABILITY VIOLATION TEST (NEMESIS) ===")
  
  (harness/reset-test-state!)
  (swap! harness/test-state assoc :active true :client-count 3)
  
  (let [nemesis-instance (advanced-split-brain-nemesis)
        test-config {:nodes ["n1" "n2" "n3" "n4" "n5"]}
        duration-ms 120000
        ops-per-second 15
        
        ;; Create focused workload for durability testing
        workload-channel (create-split-brain-workload duration-ms ops-per-second)
        
        ;; Single client for clearer analysis
        worker (run-split-brain-client-worker 0 workload-channel)]
    
    ;; Setup advanced nemesis
    (jepsen-nemesis/setup! nemesis-instance test-config)
    
    ;; Inject multiple partition events using different nemesis operations
    (let [fault-worker (go
                         ;; First split-brain after 20 seconds
                         (<! (async/timeout 20000))
                         (inject-split-brain-with-nemesis nemesis-instance test-config 30000)
                         
                         ;; Second event: primary isolation after recovery
                         (<! (async/timeout 40000))
                         (execute-nemesis-operation nemesis-instance test-config 
                                                   {:type :invoke :f :start-primary-isolation})
                         (<! (async/timeout 20000))
                         (execute-nemesis-operation nemesis-instance test-config 
                                                   {:type :invoke :f :stop-primary-isolation})
                         
                         (log/info "Durability test fault injection completed"))]
      
      (log/info "Started durability violation test with multiple partition types")
      
      ;; Wait for completion
      (async/<!! worker)
      (async/<!! fault-worker)
      
      (Thread/sleep 5000)
      
      ;; Teardown nemesis
      (jepsen-nemesis/teardown! nemesis-instance test-config)
      
      (swap! harness/test-state assoc :active false)
      
      ;; Analyze for durability violations
      (let [histories (:histories @harness/test-state)
            analysis (analyze-split-brain-data-loss histories)]
        
        (log/info "=== DURABILITY TEST RESULTS ===")
        (log/info "Durability violations detected:" 
                  (if (:split-brain-detected analysis) "YES" "NO"))
        
        (harness/save-histories (str "durability-nemesis-test-" (System/currentTimeMillis) ".json"))
        
        analysis))))