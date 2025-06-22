(ns jepsen.redis-sentinel.client
  (:require [clojure.tools.logging :refer :all]
            [jepsen.client :as client]
            [taoensso.carmine :as car :refer [wcar]]))

(defn redis-sentinel-client []
  "Standard Redis Sentinel client for read/write operations"
  (let [conn (atom nil)
        sentinel-nodes ["n1" "n2" "n3" "n4" "n5"]
        current-primary (atom "n1")
        replica-nodes (atom ["n2" "n3" "n4" "n5"])
        ip-to-hostname {"172.20.0.11" "n1"
                        "172.20.0.12" "n2"
                        "172.20.0.13" "n3"
                        "172.20.0.14" "n4"
                        "172.20.0.15" "n5"}]
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
            (let [replica (rand-nth @replica-nodes)]
              (try
                (let [replica-conn {:pool {} :spec {:host replica :port 6379}}
                      value (wcar replica-conn (car/get "test-key"))]
                  (info "Reading from replica" replica "value:" value)
                  (assoc operation :type :ok
                         :value (when value (Integer/parseInt value))
                         :node replica))
                (catch Exception e
                  (warn "Failed to read from replica" replica ":" (.getMessage e))
                  ; Try another replica on failure
                  (let [other-replica (rand-nth (remove #(= % replica) @replica-nodes))
                        other-conn {:pool {} :spec {:host other-replica :port 6379}}
                        value (wcar other-conn (car/get "test-key"))]
                    (info "Reading from backup replica" other-replica "value:" value)
                    (assoc operation :type :ok
                           :value (when value (Integer/parseInt value))
                           :node other-replica)))))
            
            :write
            (do
              ; Always refresh primary from Sentinel before writing
              (let [sentinel-node (rand-nth sentinel-nodes)]
                (try
                  (let [sentinel-conn {:pool {} :spec {:host sentinel-node :port 26379}}
                        primary-info (wcar sentinel-conn
                                           (car/redis-call ["SENTINEL" "get-master-addr-by-name" "mymaster"]))]
                    (info "Raw Sentinel response from" sentinel-node ":" primary-info
                          "Type:" (type primary-info))

                    ;; Handle different response types properly
                    (when primary-info
                      (let [parsed-info (cond
                                          ;; If it's a vector/list with IP and port
                                          (and (sequential? primary-info)
                                               (>= (count primary-info) 2))
                                          primary-info

                                          ;; If it's nested (vector of vectors)
                                          (and (sequential? primary-info)
                                               (= (count primary-info) 1)
                                               (sequential? (first primary-info)))
                                          (first primary-info)

                                          ;; Otherwise use as-is
                                          :else
                                          primary-info)]

                        (info "Parsed Sentinel info:" parsed-info)

                        (when (and (sequential? parsed-info)
                                   (>= (count parsed-info) 2))
                          (let [primary-ip (str (first parsed-info))
                                new-primary (get ip-to-hostname primary-ip primary-ip)]
                            (reset! current-primary new-primary)
                            (info "Sentinel" sentinel-node "reports primary:" new-primary "(" primary-ip ")"))))))
                  (catch Exception e
                    (warn "Failed to contact Sentinel" sentinel-node "- using cached primary:" @current-primary))))

              ; Try writing to current primary with connection retry
              (let [primary @current-primary]
                (try
                  (let [primary-conn {:pool {} :spec {:host primary :port 6379}}]
                    (wcar primary-conn (car/set "test-key" (str (:value operation))))
                    (info "Writing to primary" primary "value:" (:value operation))
                    (assoc operation :type :ok :node primary))
                  (catch Exception e
                    (warn "Failed to write to primary" primary ":" (.getMessage e))
                    ; If primary write fails, try to get fresh primary from Sentinel
                    (try
                      (let [sentinel-node (rand-nth sentinel-nodes)
                            sentinel-conn {:pool {} :spec {:host sentinel-node :port 26379}}
                            primary-info (wcar sentinel-conn
                                               (car/redis-call ["SENTINEL" "get-master-addr-by-name" "mymaster"]))
                            parsed-info (if (and (sequential? primary-info) (>= (count primary-info) 2))
                                          primary-info
                                          (first primary-info))
                            fresh-primary-ip (str (first parsed-info))
                            fresh-primary (get ip-to-hostname fresh-primary-ip fresh-primary-ip)]
                        (when (not= fresh-primary primary)
                          (info "Primary changed from" primary "to" fresh-primary "- retrying write")
                          (reset! current-primary fresh-primary))
                        (let [fresh-conn {:pool {} :spec {:host fresh-primary :port 6379}}]
                          (wcar fresh-conn (car/set "test-key" (str (:value operation))))
                          (info "Writing to fresh primary" fresh-primary "value:" (:value operation))
                          (assoc operation :type :ok :node fresh-primary)))
                      (catch Exception e2
                        (error "Failed to write after primary refresh:" (.getMessage e2))
                        (assoc operation :type :fail :error (.getMessage e2))))))))

            (do
              (warn "Unknown operation type:" (:f operation))
              (assoc operation :type :fail :error (str "Unknown operation: " (:f operation)))))
          (catch Exception e
            (error "Operation failed:" (.getMessage e))
            (assoc operation :type :fail :error (.getMessage e)))))
      
      (teardown! [this test]
        (info "Tearing down Redis Sentinel client"))
      
      (close! [this test]
        (info "Closing Redis Sentinel client")))))

(defn redis-set-sentinel-client []
  "Redis Sentinel client for SET operations (split-brain testing)"
  (let [sentinel-nodes ["n1" "n2" "n3" "n4" "n5"]
        current-primary (atom "n1")
        set-name "test-set"
        ip-to-hostname {"172.20.0.11" "n1"
                        "172.20.0.12" "n2"
                        "172.20.0.13" "n3"
                        "172.20.0.14" "n4"
                        "172.20.0.15" "n5"}]
    (reify client/Client
      (open! [this test node]
        this)

      (setup! [this test]
        (info "Setting up Redis SET Sentinel client"))

      (invoke! [this test operation]
        (try
          (case (:f operation)
            :read-set-all
            (let [all-nodes ["n1" "n2" "n3" "n4" "n5"]
                  results (atom {})]
              (doseq [node all-nodes]
                (try
                  (let [conn {:pool {} :spec {:host node :port 6379}}
                        set-values (wcar conn (car/smembers set-name))
                        int-values (sort (map #(Integer/parseInt %) set-values))]
                    (swap! results assoc node int-values)
                    (info "Read from" node ":" (count int-values) "values"))
                  (catch Exception e
                    (info "Failed to read from" node ":" (.getMessage e))
                    (swap! results assoc node :unreachable))))
              (assoc operation :type :ok :value @results))

            :write-set
            (do
              ;; Fetch primary from Sentinel
              (let [sentinel-node (rand-nth sentinel-nodes)]
                (try
                  (let [sentinel-conn {:pool {} :spec {:host sentinel-node :port 26379}}
                        primary-info (wcar sentinel-conn
                                           (car/redis-call ["SENTINEL" "get-master-addr-by-name" "mymaster"]))]
                    (info "Raw Sentinel response from" sentinel-node ":" primary-info
                          "Type:" (type primary-info))

                    (when primary-info
                      (let [parsed-info (cond
                                          (and (sequential? primary-info)
                                               (>= (count primary-info) 2))
                                          primary-info

                                          (and (sequential? primary-info)
                                               (= (count primary-info) 1)
                                               (sequential? (first primary-info)))
                                          (first primary-info)

                                          :else
                                          primary-info)]
                        (info "Parsed Sentinel info:" parsed-info)
                        (when (and (sequential? parsed-info)
                                   (>= (count parsed-info) 2))
                          (let [primary-ip (str (first parsed-info))
                                new-primary (get ip-to-hostname primary-ip primary-ip)]
                            (reset! current-primary new-primary)
                            (info "Sentinel" sentinel-node "reports primary:" new-primary "(" primary-ip ")"))))))
                  (catch Exception e
                    (warn "Failed to contact Sentinel" sentinel-node ":" (.getMessage e)))))

              ;; Perform write to current primary
              (let [primary @current-primary
                    primary-conn {:pool {} :spec {:host primary :port 6379}}
                    value (:value operation)]
                (wcar primary-conn (car/sadd set-name (str value)))
                (info "Added" value "to set on primary" primary)
                (assoc operation :type :ok :node primary :value value)))

            ;; Default case
            (do
              (warn "Unknown operation type:" (:f operation))
              (assoc operation :type :fail :error (str "Unknown operation: " (:f operation)))))
          (catch Exception e
            (error "Operation failed:" (.getMessage e))
            (assoc operation :type :fail :error (.getMessage e)))))

      (teardown! [this test]
        (info "Tearing down Redis SET Sentinel client"))

      (close! [this test]
        (info "Closing Redis SET Sentinel client")))))
