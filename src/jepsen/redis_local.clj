(ns jepsen.redis-local
    (:require [clojure.tools.logging :refer :all]
              [clojure.string :as str]
              [taoensso.carmine :as car :refer (wcar)]
              [clojure.set :as set])
    (:gen-class))

;; Local Docker-based Redis test
;; This version connects to Redis instances running in Docker containers

(def redis-nodes
  {"n1" {:host "localhost" :port 6380}
   "n2" {:host "localhost" :port 6381}
   "n3" {:host "localhost" :port 6382}
   "n4" {:host "localhost" :port 6383}
   "n5" {:host "localhost" :port 6384}})

(def sentinel-nodes
  {"s1" {:host "localhost" :port 26390}
   "s2" {:host "localhost" :port 26391}
   "s3" {:host "localhost" :port 26392}})

(defn redis-conn-spec [node-config]
  {:pool {}
   :spec {:host (:host node-config)
          :port (:port node-config)
          :timeout-ms 2000}})

(defn sentinel-conn-spec [node-config]
  {:pool {}
   :spec {:host (:host node-config)
          :port (:port node-config)
          :timeout-ms 5000}})

(defn get-master-from-sentinel
  "Ask a sentinel for the current master"
  [sentinel-config]
  (try
    (info "Attempting to get master from sentinel at" (:host sentinel-config) ":" (:port sentinel-config))
    (let [conn (sentinel-conn-spec sentinel-config)]
      (info "Connected to sentinel, executing command...")
      (let [result (wcar conn
                         (car/redis-call ["SENTINEL" "get-master-addr-by-name" "mymaster"]))]
        (info "Sentinel response:" result)
        result))
    (catch Exception e
      (warn "Failed to get master from sentinel:" (.getMessage e))
      nil)))

(defn find-current-master
  "Find the current master by asking all sentinels"
  []
  (info "Starting master discovery process...")
  (loop [sentinels (vals sentinel-nodes)]
    (when (seq sentinels)
          (info "Trying sentinel:" (first sentinels))
          (if-let [master-info (get-master-from-sentinel (first sentinels))]
            (let [host (first master-info)
                  port (Integer/parseInt (second master-info))]
              (info "Found master at" host ":" port)
              {:host host :port port})
            (do
              (info "No master found from current sentinel, trying next...")
              (recur (rest sentinels)))))))

(defn add-to-set
  "Add a value to the Redis set"
  [value]
  (try
    (if-let [master (find-current-master)]
      (let [conn (redis-conn-spec master)]
        (wcar conn (car/sadd "jepsen-test-set" (str value)))
        {:success true :value value})
      {:success false :error "No master found" :value value})
    (catch Exception e
      {:success false :error (.getMessage e) :value value})))

(defn read-set
  "Read the current set from Redis"
  []
  (try
    (if-let [master (find-current-master)]
      (let [conn (redis-conn-spec master)]
        (let [members (wcar conn (car/smembers "jepsen-test-set"))]
          {:success true :value (set (map #(Integer/parseInt %) members))}))
      {:success false :error "No master found"})
    (catch Exception e
      {:success false :error (.getMessage e)})))

(defn clear-set
  "Clear the test set"
  []
  (try
    (if-let [master (find-current-master)]
      (let [conn (redis-conn-spec master)]
        (wcar conn (car/del "jepsen-test-set"))
        {:success true})
      {:success false :error "No master found"})
    (catch Exception e
      {:success false :error (.getMessage e)})))

(defn simulate-partition
  "Simulate network partition by blocking connections to some nodes"
  []
  (println "Simulating network partition...")
  (println "In a real test, this would use iptables or Docker network manipulation")
  (println "For this demo, we'll just note that partition is 'active'"))

(defn heal-partition
  "Heal the network partition"
  []
  (println "Healing network partition...")
  (println "In a real test, this would restore network connectivity"))

(defn run-write-workload
  "Run a workload that writes integers to the set"
  [n-writes]
  (println (str "Starting write workload: " n-writes " writes"))
  (let [results (atom [])]
    (doseq [i (range n-writes)]
      (let [result (add-to-set i)]
        (swap! results conj result)
        (when (zero? (mod i 10))
              (print "."))
        (Thread/sleep 100))) ; 100ms between writes
    (println)
    @results))

(defn analyze-results
  "Analyze the test results"
  [write-results final-set]
  (let [attempted (count write-results)
        acknowledged (count (filter :success write-results))
        successful-values (set (map :value (filter :success write-results)))
        final-values (if (:success final-set) (:value final-set) #{})
        survivors (set/intersection successful-values final-values)
        lost (set/difference successful-values final-values)]

    (println "\n=== ANALYSIS ===")
    (println (str attempted " total writes attempted"))
    (println (str acknowledged " writes acknowledged"))
    (println (str (count survivors) " writes survived"))
    (println (str (count lost) " acknowledged writes lost!"))

    (when (seq lost)
          (println "Lost values:" (sort (take 20 lost))))

    (println (str "Acknowledgment rate: " (float (/ acknowledged attempted))))
    (println (str "Loss rate: " (float (/ (count lost) acknowledged))))

    {:attempted attempted
     :acknowledged acknowledged
     :survivors (count survivors)
     :lost (count lost)
     :lost-values lost}))

(defn demo-test
  "Run a demonstration of the Redis partition test"
  []
  (println "=== JEPSEN REDIS DEMO ===")
  (println "This demo simulates the Redis partition test locally using Docker")
  (println)

  ;; Clear any existing data
  (println "Clearing existing data...")
  (clear-set)

  ;; Initial write phase
  (println "Phase 1: Initial writes (no partition)")
  (let [initial-results (run-write-workload 50)]
    (Thread/sleep 1000)

    ;; Simulate partition
    (println "\nPhase 2: Simulating network partition")
    (simulate-partition)

    ;; Continue writing during partition
    (println "Phase 3: Writes during partition")
    (let [partition-results (run-write-workload 100)]
      (Thread/sleep 2000)

      ;; Heal partition
      (println "\nPhase 4: Healing partition")
      (heal-partition)
      (Thread/sleep 2000)

      ;; Final read
      (println "Phase 5: Final read")
      (let [final-set (read-set)
            all-results (concat initial-results partition-results)]

        ;; Analyze results
        (analyze-results all-results final-set)))))

(defn check-cluster-status
  "Check the status of the Redis cluster"
  []
  (println "=== CLUSTER STATUS ===")

  ;; Check master
  (if-let [master (find-current-master)]
    (println (str "Current master: " (:host master) ":" (:port master)))
    (println "No master found!"))

  ;; Check each Redis node
  (println "\nRedis nodes:")
  (doseq [[name config] redis-nodes]
    (try
      (let [conn (redis-conn-spec config)]
        (wcar conn (car/ping))
        (println (str "  " name " (" (:host config) ":" (:port config) ") - OK")))
      (catch Exception e
        (println (str "  " name " (" (:host config) ":" (:port config) ") - ERROR: " (.getMessage e))))))

  ;; Check sentinels
  (println "\nSentinel nodes:")
  (doseq [[name config] sentinel-nodes]
    (try
      (let [conn (sentinel-conn-spec config)]
        (wcar conn (car/ping))
        (println (str "  " name " (" (:host config) ":" (:port config) ") - OK")))
      (catch Exception e
        (println (str "  " name " (" (:host config) ":" (:port config) ") - ERROR: " (.getMessage e)))))))

(defn -main
  "Main entry point"
  [& args]
  (case (first args)
        "status" (check-cluster-status)
        "demo" (demo-test)
        "clear" (do (clear-set) (println "Set cleared"))
        (do
          (println "Usage:")
          (println "  lein run local status  - Check cluster status")
          (println "  lein run local demo    - Run demo test")
          (println "  lein run local clear   - Clear test data"))))