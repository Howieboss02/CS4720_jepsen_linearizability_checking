(ns jepsen.redis-sentinel.main
  (:require [clojure.tools.logging :refer :all]
            [jepsen.control :as c]
            [jepsen.redis-sentinel.tests.simple :as simple-tests]
            [jepsen.redis-sentinel.tests.partition :as partition-tests]
            [jepsen.redis-sentinel.tests.failover :as failover-tests]
            [jepsen.redis-sentinel.tests.network :as network-tests]
            [jepsen.redis-sentinel.tests.latency :as latency-tests]))

(defn verify-prerequisites []
  (info "Checking prerequisites...")
  (let [nodes ["n1" "n2" "n3" "n4" "n5"]
        ssh-failures (atom 0)
        service-failures (atom 0)]
    
    ;; Check SSH connectivity
    (doseq [node nodes]
      (try
        (c/on node (c/exec :uptime))
        (catch Exception e
          (warn "SSH failed for" node)
          (swap! ssh-failures inc))))
    
    ;; Check Redis/Sentinel services
    (doseq [node nodes]
      (try
        (c/on node
              (let [redis-running? (try (c/exec :pgrep :redis-server) true
                                       (catch Exception _ false))
                    sentinel-running? (try (c/exec :pgrep :redis-sentinel) true
                                          (catch Exception _ false))]
                (when-not (and redis-running? sentinel-running?)
                  (warn "Service issue on" node "- Redis:" redis-running? "Sentinel:" sentinel-running?)
                  (swap! service-failures inc))))
        (catch Exception e
          (swap! service-failures inc))))
    
    (let [ssh-ok (= @ssh-failures 0)
          services-ok (< @service-failures 3)]  ; Allow some failures
      (when-not ssh-ok
        (error "SSH connectivity failed for" @ssh-failures "nodes")
        (System/exit 1))
      (when-not services-ok
        (warn "Service issues detected on" @service-failures "nodes - tests may fail"))
      (info "Prerequisites OK -" (- 5 @ssh-failures) "nodes accessible," 
            (- 5 @service-failures) "nodes with services running"))))

(defn run-test [test-name]
  (info "🚀 Starting test:" test-name)
  (case test-name
    ;; Basic Tests
    "simple" (simple-tests/run-simple-test)
    "intensive" (simple-tests/run-intensive-test)
    "concurrent" (simple-tests/run-concurrent-test)
    
    ;; Partition Tolerance Tests
    "split-brain" (partition-tests/run-split-brain-test)
    "set-split-brain" (partition-tests/run-set-split-brain-test)
    
    ;; Failover Tests
    "majorities-ring" (failover-tests/run-majorities-ring-test)
    
    ;; Network Instability Tests
    "flapping-partitions" (network-tests/run-flapping-partitions-test)
    "bridge-partitions" (network-tests/run-bridge-partitions-test)
    
    ;; Latency & Timeout Tests
    "latency-injection" (latency-tests/run-latency-injection-test)
    "extreme-latency" (latency-tests/run-extreme-latency-injection-test)
    
    ;; Default case
    (do
      (error "❌ Unknown test:" test-name)
      (println "")
      (System/exit 1))))

(defn -main [& args]
  
  (if (empty? args)
    (do
      (info "🎯 No test specified - running interactive mode...")
      (println "")
      (println "Press Enter to run 'simple' test or Ctrl+C to exit...")
      (read-line)
      (println "🚀 Running default 'simple' test...")
      (c/with-ssh {:username "root"
                   :private-key-path "/root/.ssh/id_rsa"
                   :strict-host-key-checking false
                   :ssh-opts ["-o" "StrictHostKeyChecking=no"
                              "-o" "UserKnownHostsFile=/dev/null"
                              "-o" "GlobalKnownHostsFile=/dev/null"
                              "-o" "LogLevel=ERROR"]}
        (verify-prerequisites)
        (run-test "simple")))
    
    (let [test-name (first args)]
      (c/with-ssh {:username "root"
                   :private-key-path "/root/.ssh/id_rsa"
                   :strict-host-key-checking false
                   :ssh-opts ["-o" "StrictHostKeyChecking=no"
                              "-o" "UserKnownHostsFile=/dev/null"
                              "-o" "GlobalKnownHostsFile=/dev/null"
                              "-o" "LogLevel=ERROR"]}
        (verify-prerequisites)
        (run-test test-name))))
  
  (info "🎉 Test execution completed!")
  (System/exit 0))