(ns back-channeling.main
  (:gen-class)
  (:require [duct.core :as duct]
            [clojure.java.io :as io]))

(duct/load-hierarchy)

(defn -main [& args]
  (let [keys (or (duct/parse-keys args) [:duct/daemon])]
    (-> (duct/read-config (io/resource "back_channeling/config.edn"))
        (duct/prep keys)
        (duct/exec keys))))
