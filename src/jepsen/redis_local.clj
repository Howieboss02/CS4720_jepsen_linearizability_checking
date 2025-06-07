(ns jepsen.redis-local
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [taoensso.carmine :as car :refer (wcar)]
            [clojure.set :as set])
  (:gen-class))

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
    (let [conn (sentinel-conn-spec sentinel-config)
          result (wcar conn (car/redis-call ["SENTINEL" "get-master-addr-by-name" "mymaster"]))]
      (info "Sentinel response:" result)
      result)
    (catch Exception e
      (warn e "Failed to get master from sentinel:" sentinel-config)
      nil)))

(defn find-current-master
  "Find the current master by asking all sentinels"
  []
  (info "Looking for master...")
  (loop [sentinels (seq (vals sentinel-nodes))]
    (if (empty? sentinels)
      (do
        (warn "No sentinels returned a master")
        nil)
      (let [sentinel (first sentinels)
            result (get-master-from-sentinel sentinel)]
        (if (and (vector? result) (= 2 (count result)))
          (let [[host port-str] result]
            (try
              (let [port (Integer/parseInt port-str)]
                (info "Found master at" host ":" port)
                {:host host :port port})
              (catch NumberFormatException e
                (warn e "Invalid port in sentinel response:" port-str)
                )))
          (do
            (info "No valid master from sentinel, trying next...")
            ))))))

(defn add-to-set
  "Add a value to the Redis set"
  [value]
  (try
    (if-let [master (find-current-master)]
      (try
        (let [conn (redis-conn-spec master)]
          (wcar conn (car/sadd "jepsen-test-set" (str value)))
          (info "Added value" value "to set at" (:host master) ":" (:port master))
          {:success true :value value})
        (catch Exception e
          (error e "Exception while connecting to Redis or writing")
          {:success false :error (.getMessage e) :value value}))
      (do
        (info "No master found, cannot add value")
        {:success false :error "No master found" :value value}))
    (catch Exception e
      (error e "Unhandled exception in add-to-set")
      {:success false :error (.getMessage e) :value value})))

(defn read-set
  "Read the current set from Redis"
  []
  (try
    (if-let [master (find-current-master)]
      (try
        (let [conn (redis-conn-spec master)
              members (wcar conn (car/smembers "jepsen-test-set"))]
          (info "Read set from" (:host master) ":" (:port master) "with members:" members)
          {:success true :value (set (map #(Integer/parseInt %) members))})
        (catch Exception e
          (error e "Exception while reading from Redis")
          {:success false :error (.getMessage e)}))
      (do
        (info "No master found, cannot read set")
        {:success false :error "No master found"}))
    (catch Exception e
      (error e "Unhandled exception in read-set")
      {:success false :error (.getMessage e)})))

(defn clear-set
  "Clear the test set"
  []
  (try
    (if-let [master (find-current-master)]
      (try
        (let [conn (redis-conn-spec master)]
          (wcar conn (car/del "jepsen-test-set"))
          (info "Cleared the Redis set at" (:host master) ":" (:port master))
          {:success true})
        (catch Exception e
          (error e "Error clearing Redis set")
          {:success false :error (.getMessage e)}))
      (do
        (info "No master found, cannot clear set")
        {:success false :error "No master found"}))
    (catch Exception e
      (error e "Unhandled exception in clear-set")
      {:success false :error (.getMessage e)})))

(defn simulate-partition []
  (println "Simulating network partition...")
  (println "In a real test, this would use iptables or Docker network manipulation"))

(defn heal-partition []
  (println "Healing network partition...")
  (println "In a real test, this would restore network connectivity"))

(defn run-write-workload [n-writes]
  (println (str "Starting write workload: " n-writes " writes"))
  (let [results (atom [])]
    (doseq [i (range n-writes)]
      (let [result (add-to-set i)]
        (swap! results conj result)
        (when (zero? (mod i 10)) (print "."))
        (flush)
        (Thread/sleep 100)))
    (println)
    @results))

(defn analyze-results [write-results final-set]
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
    (println (str "Loss rate: " (float (/ (count lost) (max 1 acknowledged)))))

    {:attempted attempted
     :acknowledged acknowledged
     :survivors (count survivors)
     :lost (count lost)
     :lost-values lost}))

(defn demo-test []
  (println "=== JEPSEN REDIS DEMO ===")
  (println "This demo simulates the Redis partition test locally using Docker")
  (println "\nClearing existing data...")
  (clear-set)

  (println "Phase 1: Initial writes (no partition)")
  (let [initial-results (run-write-workload 50)]
    (Thread/sleep 1000)

    (println "\nPhase 2: Simulating network partition")
    (simulate-partition)

    (println "Phase 3: Writes during partition")
    (let [partition-results (run-write-workload 100)]
      (Thread/sleep 2000)

      (println "\nPhase 4: Healing partition")
      (heal-partition)
      (Thread/sleep 2000)

      (println "Phase 5: Final read")
      (let [final-set (read-set)
            all-results (concat initial-results partition-results)]
        (analyze-results all-results final-set)))))

(defn check-cluster-status []
  (println "=== CLUSTER STATUS ===")
  (if-let [master (find-current-master)]
    (println (str "Current master: " (:host master) ":" (:port master)))
    (println "No master found!"))

  (println "\nRedis nodes:")
  (doseq [[name config] redis-nodes]
    (try
      (let [conn (redis-conn-spec config)]
        (wcar conn (car/ping))
        (println (str "  " name " (" (:host config) ":" (:port config) ") - OK")))
      (catch Exception e
        (println (str "  " name " - ERROR: " (.getMessage e)))
        (error e "Error checking Redis node" name))))

  (println "\nSentinel nodes:")
  (doseq [[name config] sentinel-nodes]
    (try
      (let [conn (sentinel-conn-spec config)]
        (wcar conn (car/ping))
        (println (str "  " name " (" (:host config) ":" (:port config) ") - OK")))
      (catch Exception e
        (println (str "  " name " - ERROR: " (.getMessage e)))
        (error e "Error checking Sentinel node" name)))))

(defn -main [& args]
  (case (first args)
    "status" (check-cluster-status)
    "demo"   (demo-test)
    "clear"  (do (clear-set) (println "Set cleared"))
    (do
      (println "Usage:")
      (println "  lein run local status  - Check cluster status")
      (println "  lein run local demo    - Run demo test")
      (println "  lein run local clear   - Clear test data"))))
