(ns back-channeling.main
  (:gen-class)
  (:require [duct.core :as duct]
            [duct.core.env :refer [env]]
            [clojure.java.io :as io]))

(duct/load-hierarchy)

(defn -main [& args]
  (let [keys (or (duct/parse-keys args) [:back-channeling.migrator/schema :duct/daemon])]
    (-> (duct/read-config (io/resource (or (env "BC_CONFIG_PATH")
                                           "back_channeling/config.edn")))
        (duct/prep keys)
        (duct/exec keys))))
