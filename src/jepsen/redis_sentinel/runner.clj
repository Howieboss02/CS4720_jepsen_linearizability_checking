(ns jepsen.redis-sentinel.runner
  (:require [clojure.core.async :as async :refer [go chan >! <! alts!]]
            [clojure.tools.logging :as log]
            [jepsen.redis-sentinel.test-harness :as harness]
            [jepsen.redis-sentinel.client :as client]
            [jepsen.redis-sentinel.workloads :as workloads]))

(defn execute-operation [client operation]
  "Execute a single operation using the client"
  (try
    (case (:type operation)
      :read (client/read-key client (:key operation))
      :write (client/write-key client (:key operation) (:value operation))
      :increment (client/increment-key client (:key operation))
      :delete (client/delete-key client (:key operation))
      :scan (client/scan-keys client (:pattern operation "key-*"))
      :cas (client/cas-key client (:key operation)
                           (:old-value operation) (:new-value operation))
      (log/warn "Unknown operation type:" (:type operation)))
    (catch Exception e
      (log/error "Operation failed:" e))))

(defn run-client-worker
  "Runs a single client worker with given workload"
  [client-id workload-channel]
  (go
    (let [client (client/create-client client-id)]
      (log/info "Starting smart client worker" client-id)
      (loop []
        (when-let [operation (<! workload-channel)]
          (execute-operation client operation)
          (recur)))
      (log/info "Client worker" client-id "finished"))))
(defn start-fault-injection
  "Simple fault injection starter - delegates to full implementation"
  [fault-type fault-frequency fault-duration test-duration]
  (require 'jepsen.redis-sentinel.fault-injection)
  ((resolve 'jepsen.redis-sentinel.fault-injection/start-fault-injection)
   fault-type fault-frequency fault-duration test-duration))

(defn analyze-fault-tolerance
  "Basic fault tolerance analysis"
  [histories]
  (let [total-ops (count histories)
        failed-ops (count (filter #(= (:type %) :fail) histories))
        success-rate (if (> total-ops 0)
                       (* 100.0 (/ (- total-ops failed-ops) total-ops))
                       0)]

    (log/info "=== Fault Tolerance Analysis ===")
    (log/info "Total operations:" total-ops)
    (log/info "Failed operations:" failed-ops)
    (log/info "Success rate:" (format "%.2f%%" success-rate))

    {:total-ops total-ops
     :failed-ops failed-ops
     :success-rate success-rate}))
(defn run-fault-tolerant-client-worker
  "Runs a fault-tolerant client worker for fault injection tests"
  [client-id workload-channel]
  (go
    (let [client (client/create-fault-tolerant-client client-id)]
      (log/info "Starting fault-tolerant client worker" client-id)
      (loop []
        (when-let [operation (<! workload-channel)]
          (execute-operation client operation)
          (recur)))
      (log/info "Fault-tolerant client worker" client-id "finished"))))

(defn run-linearizability-test [config]
  "Main test runner for linearizability testing"
  (let [{:keys [client-count duration-ms workload-type ops-per-second workload-params]} config]

    ;; Reset and prepare test state
    (harness/reset-test-state!)
    (swap! harness/test-state assoc :active true :client-count client-count)

    ;; Create workload channels
    (let [workload-channels (for [i (range client-count)]
                              (case workload-type
                                :register (workloads/register-workload
                                           duration-ms ops-per-second
                                           (get workload-params :register-count 10))
                                :counter (workloads/counter-workload
                                          duration-ms ops-per-second
                                          (get workload-params :counter-count 5))
                                :mixed (workloads/mixed-workload
                                        duration-ms ops-per-second
                                        (get workload-params :key-count 20))
                                :read-only (workloads/read-only-workload
                                            duration-ms ops-per-second
                                            (get workload-params :key-count 20))
                                :write-only (workloads/write-only-workload
                                             duration-ms ops-per-second
                                             (get workload-params :key-count 20))))

          ;; Start workers (all using smart clients)
          workers (for [[i workload-channel] (map-indexed vector workload-channels)]
                    (run-client-worker i workload-channel))]

      (log/info "Started" client-count "smart clients for" duration-ms "ms")
      (log/info "Smart clients automatically route reads to replicas and writes to primary")

      ;; Wait for completion
      (doseq [worker workers]
        (async/<!! worker))

      ;; Finalize
      (swap! harness/test-state assoc :active false)
      (let [total-ops (count (:histories @harness/test-state))]
        (log/info "Test completed. Total operations:" total-ops)

        ;; Log operation type distribution
        (let [histories (:histories @harness/test-state)
              op-types (frequencies (map :f histories))]
          (log/info "Operation type distribution:" op-types))

        ;; Save results
        (harness/save-histories (str "linearizability-test-" (System/currentTimeMillis) ".json"))

        (:histories @harness/test-state)))))

(defn run-read-write-separated-test
  "Test that explicitly separates reads and writes using smart clients"
  [config]
  (let [{:keys [client-count duration-ms ops-per-second workload-params]} config
        read-client-count (int (* client-count 0.7))  ; 70% readers
        write-client-count (- client-count read-client-count)]  ; 30% writers

    (harness/reset-test-state!)
    (swap! harness/test-state assoc :active true :client-count client-count)

    (let [;; Create separate workloads for reads and writes
          read-workloads (for [i (range read-client-count)]
                           (workloads/read-only-workload duration-ms ops-per-second
                                                         (get workload-params :key-count 20)))
          write-workloads (for [i (range write-client-count)]
                            (workloads/write-only-workload duration-ms ops-per-second
                                                           (get workload-params :key-count 20)))

          ;; Start read workers (smart clients will route to replicas)
          read-workers (for [[i workload] (map-indexed vector read-workloads)]
                         (run-client-worker (str "reader-" i) workload))

          ;; Start write workers (smart clients will route to primary)
          write-workers (for [[i workload] (map-indexed vector write-workloads)]
                          (run-client-worker (str "writer-" i) workload))]

      (log/info "Started" read-client-count "readers and" write-client-count "writers")
      (log/info "Smart clients automatically handle read/write routing")

      ;; Wait for all workers
      (doseq [worker (concat read-workers write-workers)]
        (async/<!! worker))

      (swap! harness/test-state assoc :active false)
      (harness/save-histories (str "read-write-separated-test-" (System/currentTimeMillis) ".json"))

      (:histories @harness/test-state))))

(defn run-replica-consistency-test
  "Test that checks consistency across all replicas using smart clients"
  [config]
  (let [{:keys [duration-ms ops-per-second]} config
        write-client-count 2
        read-client-count 8]  ; 2 writers, 8 readers

    (harness/reset-test-state!)
    (swap! harness/test-state assoc :active true
           :client-count (+ write-client-count read-client-count))

    (let [;; Create workloads
          write-workloads (for [i (range write-client-count)]
                            (workloads/register-workload duration-ms ops-per-second 5))
          read-workloads (for [i (range read-client-count)]
                           (workloads/read-only-workload duration-ms (* ops-per-second 2) 5))

          ;; Start writers (smart clients route to primary)
          write-workers (for [[i workload] (map-indexed vector write-workloads)]
                          (run-client-worker (str "writer-" i) workload))

          ;; Start readers (smart clients distribute across replicas)
          read-workers (for [[i workload] (map-indexed vector read-workloads)]
                         (run-client-worker (str "reader-" i) workload))]

      (log/info "Started consistency test: 2 writers, 8 readers")
      (log/info "Smart clients handle automatic replica distribution")

      ;; Wait for completion
      (doseq [worker (concat write-workers read-workers)]
        (async/<!! worker))

      (swap! harness/test-state assoc :active false)
      (harness/save-histories (str "replica-consistency-test-" (System/currentTimeMillis) ".json"))

      (:histories @harness/test-state))))

(defn run-high-load-test
  "High load test using all replicas efficiently"
  [config]
  (let [{:keys [client-count duration-ms ops-per-second workload-params]} config]

    (harness/reset-test-state!)
    (swap! harness/test-state assoc :active true :client-count client-count)

    (let [;; Mix of workload types
          workload-channels (for [i (range client-count)]
                              (case (mod i 3)
                                0 (workloads/read-only-workload duration-ms ops-per-second
                                                                (get workload-params :key-count 50))
                                1 (workloads/register-workload duration-ms ops-per-second
                                                               (get workload-params :register-count 20))
                                2 (workloads/counter-workload duration-ms ops-per-second
                                                              (get workload-params :counter-count 10))))

          workers (for [[i workload-channel] (map-indexed vector workload-channels)]
                    (run-client-worker i workload-channel))]

      (log/info "Started high-load test with" client-count "smart clients")

      ;; Wait for completion
      (doseq [worker workers]
        (async/<!! worker))

      (swap! harness/test-state assoc :active false)
      (harness/save-histories (str "high-load-test-" (System/currentTimeMillis) ".json"))

      (:histories @harness/test-state))))

(defn run-fault-injection-test
  "Enhanced test runner with fault injection capabilities"
  [config]
  (let [{:keys [client-count duration-ms workload-type ops-per-second
                fault-type fault-frequency fault-duration workload-params]} config]

    (log/info "Starting fault injection test with" fault-type "faults")
    (harness/reset-test-state!)
    (swap! harness/test-state assoc :active true :client-count client-count)

    ;; Create workload channels using existing workloads
    (let [workload-channels (for [i (range client-count)]
                              (case workload-type
                                :register (workloads/register-workload
                                           duration-ms ops-per-second
                                           (get workload-params :register-count 10))
                                :counter (workloads/counter-workload
                                          duration-ms ops-per-second
                                          (get workload-params :counter-count 5))
                                :mixed (workloads/mixed-workload
                                        duration-ms ops-per-second
                                        (get workload-params :key-count 20))
                                :read-only (workloads/read-only-workload
                                            duration-ms ops-per-second
                                            (get workload-params :key-count 20))
                                :write-only (workloads/write-only-workload
                                             duration-ms ops-per-second
                                             (get workload-params :key-count 20))))

          ;; Start fault-tolerant workers
          workers (for [[i workload-channel] (map-indexed vector workload-channels)]
                    (run-fault-tolerant-client-worker i workload-channel))

          ;; Start fault injection
          fault-worker (start-fault-injection fault-type fault-frequency
                                              fault-duration duration-ms)]

      (log/info "Started" client-count "fault-tolerant clients with" fault-type "fault injection")

      ;; Wait for completion
      (doseq [worker workers]
        (async/<!! worker))

      ;; Stop fault injection
      (when fault-worker
        (async/close! fault-worker))

      ;; Finalize
      (swap! harness/test-state assoc :active false)
      (let [total-ops (count (:histories @harness/test-state))]
        (log/info "Fault injection test completed. Total operations:" total-ops)

        ;; Analyze fault tolerance
        (analyze-fault-tolerance (:histories @harness/test-state))

        ;; Save results with fault info
        (harness/save-histories (str "results/fault-testing/fault-injection-"
                                     (name fault-type) "-"
                                     (System/currentTimeMillis) ".json"))

        (:histories @harness/test-state)))))

;; ADD : Simple fault injection starter (you'll add full implementation in fault_injection.clj)
 