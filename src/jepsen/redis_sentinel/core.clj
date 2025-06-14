(ns jepsen.redis-sentinel.core
  (:require [clojure.tools.logging :as log]
            [jepsen.redis-sentinel.runner :as runner]
            [jepsen.redis-sentinel.test-harness :as harness]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [knossos.model :as model]
            [knossos.linear :as linear]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:gen-class))

(def test-configurations
  {:basic-linearizability
   {:client-count 5
    :duration-ms 30000
    :workload-type :register
    :ops-per-second 10
    :workload-params {:register-count 10}}
   
   :high-contention
   {:client-count 10
    :duration-ms 60000
    :workload-type :counter
    :ops-per-second 50
    :workload-params {:counter-count 5}}
   
   :all-replicas-stress
   {:client-count 20
    :duration-ms 60000
    :workload-type :mixed
    :ops-per-second 30
    :workload-params {:key-count 50}}
   
   :read-heavy-workload
   {:client-count 16
    :duration-ms 45000
    :workload-type :read-only
    :ops-per-second 40
    :workload-params {:key-count 30}}
   
   :write-heavy-workload
   {:client-count 8
    :duration-ms 45000
    :workload-type :write-only
    :ops-per-second 25
    :workload-params {:key-count 20}}
   
   :high-load-mixed
   {:client-count 25
    :duration-ms 90000
    :workload-type :mixed
    :ops-per-second 40
    :workload-params {:key-count 100 :register-count 30 :counter-count 15}}})

(def fault-test-configurations
  {:network-partition-mild
   {:client-count 8
    :duration-ms 120000
    :workload-type :register
    :ops-per-second 15
    :fault-type :network-partition
    :fault-frequency 30
    :fault-duration 10000
    :workload-params {:register-count 10}}
   
   :process-crash-test
   {:client-count 10
    :duration-ms 150000
    :workload-type :mixed
    :ops-per-second 25
    :fault-type :process-crash
    :fault-frequency 25
    :fault-duration 8000
    :workload-params {:key-count 20}}
   
   :chaos-test
   {:client-count 15
    :duration-ms 300000
    :workload-type :register
    :ops-per-second 25
    :fault-type :chaos
    :fault-frequency 20
    :fault-duration 8000
    :workload-params {:register-count 25}}})

;; Knossos linearizability checking functions
(defn load-history [file-path]
  "Load a JSON history file and convert string keys to keywords"
  (try
    (let [file-content (slurp file-path)
          parsed (json/parse-string file-content true)
          keywordized (map #(walk/keywordize-keys %) parsed)
          ;; Use client numbers directly as process IDs and ensure operation types are keywords
          processed (map #(-> %
                             (update :type keyword)
                             (update :f keyword)
                             (assoc :process (:client %))
                             (dissoc :client)) 
                        keywordized)]
      processed)
    (catch Exception e
      (log/error "Failed to load history file:" file-path "Error:" (.getMessage e))
      nil)))

(defn get-model-for-workload [workload-type]
  "Get the appropriate Knossos model for the workload type"
  (case workload-type
    :register (model/register)
    :counter (model/register) ; Use register model for counter-like operations
    :mixed (model/register)
    :read-only (model/register)
    :write-only (model/register)
    (model/register))) ; Default to register

(defn find-latest-history-file []
  "Find the most recent history file in results/histories/"
  (let [histories-dir (io/file "results/histories")]
    (when (.exists histories-dir)
      (let [files (->> (.listFiles histories-dir)
                       (filter #(.isFile %))
                       (filter #(str/ends-with? (.getName %) ".json"))
                       (sort-by #(.lastModified %) >))]
        (when (seq files)
          (.getPath (first files)))))))

(defn check-linearizability 
  ([file-path] (check-linearizability file-path :register))
  ([file-path workload-type]
   (log/info "Checking linearizability for:" file-path)
   (if-let [history (load-history file-path)]
     (let [model (get-model-for-workload workload-type)
           result (linear/analysis model history)]
       (log/info "=== LINEARIZABILITY ANALYSIS RESULTS ===")
       (log/info "File:" file-path)
       (log/info "Operations analyzed:" (count history))
       (log/info "Workload type:" workload-type)
       (log/info "Model used:" (type model))
       
       (if (:valid? result)
         (do
           (log/info "✓ RESULT: History is LINEARIZABLE!")
           (log/info "All operations can be arranged in a valid linear order."))
         (do
           (log/error "✗ RESULT: LINEARIZABILITY VIOLATION DETECTED!")
           (log/error "Full Knossos analysis result:" result)
           (when-let [error (:error result)]
             (log/error "Error details:" error))
           (when-let [counterexample (:counterexample result)]
             (log/error "Counterexample operations:")
             (doseq [op counterexample]
               (log/error "  Operation:" op)))
           (when-let [model-state (:model-state result)]
             (log/error "Model state at violation:" model-state))
           (when-let [history (:history result)]
             (log/error "History up to violation:")
             (doseq [op history]
               (log/error "  " op)))))
       
       (log/info "=== END ANALYSIS ===")
       result)
     (log/error "Could not load history file:" file-path))))

(defn check-all-histories []
  "Check linearizability for all history files in results/histories/"
  (let [histories-dir (io/file "results/histories")]
    (if (.exists histories-dir)
      (let [history-files (->> (.listFiles histories-dir)
                               (filter #(.isFile %))
                               (filter #(str/ends-with? (.getName %) ".json"))
                               (map #(.getPath %)))]
        (if (seq history-files)
          (do
            (log/info "Found" (count history-files) "history files to analyze")
            (doseq [file-path history-files]
              (check-linearizability file-path)
              (println "---")))
          (log/warn "No history files found in results/histories/")))
      (log/error "results/histories/ directory does not exist"))))

(defn run-test-suite []
  (doseq [[test-name config] test-configurations]
    (log/info "=== Running test:" test-name "===")
    (try
      (runner/run-linearizability-test config)
      (log/info "Test" test-name "completed successfully")
      (catch Exception e
        (log/error "Test" test-name "failed:" e)))
    (Thread/sleep 5000)))

(defn run-specialized-tests []
  "Run tests specifically designed for smart client testing"
  (log/info "=== Running Smart Client Specialized Tests ===")
  
  ;; Test 1: Read/Write Separation
  (log/info "Running read/write separated test...")
  (runner/run-read-write-separated-test 
    {:client-count 15 :duration-ms 45000 :ops-per-second 20 
     :workload-params {:key-count 30}})
  
  (Thread/sleep 3000)
  
  ;; Test 2: Replica Consistency
  (log/info "Running replica consistency test...")
  (runner/run-replica-consistency-test 
    {:duration-ms 60000 :ops-per-second 15})
  
  (Thread/sleep 3000)
  
  ;; Test 3: High Load Test
  (log/info "Running high load test...")
  (runner/run-high-load-test
    {:client-count 30 :duration-ms 90000 :ops-per-second 35
     :workload-params {:key-count 100 :register-count 40 :counter-count 20}})
  
  (log/info "All specialized tests completed!"))

(defn run-fault-injection-suite
  "Run fault injection tests using existing infrastructure"
  []
  (log/info "=== Starting Fault Injection Test Suite ===")
  (doseq [[test-name config] fault-test-configurations]
    (log/info "=== Running fault test:" test-name "===")
    (try
      (runner/run-fault-injection-test config)
      (log/info "Fault test" test-name "completed successfully")
      (catch Exception e
        (log/error "Fault test" test-name "failed:" e)))
    (Thread/sleep 10000)))

(defn run-specific-fault-test
  "Run a specific fault test by name"
  [test-name]
  (if-let [config (get fault-test-configurations test-name)]
    (do
      (log/info "Running fault test:" test-name)
      (runner/run-fault-injection-test config))
    (log/error "Unknown fault test name:" test-name)))

(defn -main [& args]
  (cond
    (empty? args) 
    (run-test-suite)
    
    (= (first args) "check-linearizability")
    (cond
      ;; Check specific file
      (= (count args) 2)
      (check-linearizability (second args))
      
      ;; Check specific file with workload type
      (= (count args) 3)
      (check-linearizability (second args) (keyword (nth args 2)))
      
      ;; Check latest file
      :else
      (if-let [latest-file (find-latest-history-file)]
        (check-linearizability latest-file)
        (log/error "No history files found")))
    
    (= (first args) "check-all")
    (check-all-histories)
    
    (= (first args) "specialized") 
    (run-specialized-tests)
    
    (= (first args) "faults") 
    (run-fault-injection-suite)
    
    (= (first args) "chaos") 
    (run-specific-fault-test :chaos-test)
    
    (contains? test-configurations (keyword (first args))) 
    (runner/run-linearizability-test (get test-configurations (keyword (first args))))
    
    (contains? fault-test-configurations (keyword (first args)))
    (run-specific-fault-test (keyword (first args)))
    
    :else 
    (do
      (log/error "Unknown command:" (first args))
      (println "Usage:")
      (println "  lein run                                    - Run all tests")
      (println "  lein run basic-linearizability              - Run specific test")
      (println "  lein run check-linearizability              - Check latest history file")
      (println "  lein run check-linearizability <file>       - Check specific history file")
      (println "  lein run check-linearizability <file> <type> - Check with specific workload type")
      (println "  lein run check-all                          - Check all history files")
      (println "  lein run specialized                        - Run specialized tests")
      (println "  lein run faults                             - Run fault injection tests"))))