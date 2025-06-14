;; filepath: check_linearizability.clj
(ns check-linearizability
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [knossos.model :as model]
            [knossos.linear :as linear]))

(def history-file "/../results/histories/linearizability-test-1749904807477.json")

(defn load-history [file]
  (with-open [r (io/reader file)]
    (json/parse-stream r true)))

(defn -main [& _]
  (let [history (load-history history-file)
        m (model/register)]
    (println "Checking linearizability for:" history-file)
    (let [result (linear/analysis m history)]
      (if (:valid? result)
        (println "History is linearizable!")
        (do
          (println "Linearizability violation found!")
          (println (:error result)))))))