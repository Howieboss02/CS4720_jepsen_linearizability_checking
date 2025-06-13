(ns jepsen.redis-sentinel.test-harness
  (:require [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [clj-time.core :as time]
            [clj-time.format :as f]))

;; Connection definitions
(def redis-nodes
  {:primary   {:pool {} :spec {:host "redis-primary" :port 6379}}
   :replica1  {:pool {} :spec {:host "redis-replica1" :port 6379}}
   :replica2  {:pool {} :spec {:host "redis-replica2" :port 6379}}
   :replica3  {:pool {} :spec {:host "redis-replica3" :port 6379}}
   :replica4  {:pool {} :spec {:host "redis-replica4" :port 6379}}})

(def sentinel-nodes
  {:sentinel1 {:pool {} :spec {:host "redis-sentinel1" :port 26379}}
   :sentinel2 {:pool {} :spec {:host "redis-sentinel2" :port 26380}}
   :sentinel3 {:pool {} :spec {:host "redis-sentinel3" :port 26381}}})

;; Global state management
(defonce test-state (atom {:active false
                          :histories []
                          :client-count 0
                          :operation-count 0}))

(defn timestamp []
  (System/nanoTime))

(defn record-operation [op]
  (swap! test-state update :histories conj (assoc op :timestamp (timestamp))))

(defn save-histories 
  ([filename]
   (save-histories filename (:histories @test-state)))
  ([filename histories]
   (let [results-dir "results/histories/"
         full-path (str results-dir filename)]
     (clojure.java.io/make-parents full-path)
     (spit full-path (json/generate-string histories {:pretty true}))
     (log/info "Saved" (count histories) "operations to" full-path))))

(defn reset-test-state! []
  (reset! test-state {:active false :histories [] :client-count 0 :operation-count 0}))