(ns back-channeling.main
  (:gen-class)
  (:require [com.stuartsierra.component :as component]
            [duct.middleware.errors :refer [wrap-hide-errors]]
            [duct.util.runtime :refer [add-shutdown-hook]]
            [meta-merge.core :refer [meta-merge]]
            [back-channeling.config :as config]
            [back-channeling.system :refer [new-system]]))

(def prod-config
  {:app {:middleware     [[wrap-hide-errors :internal-error]]
         :internal-error "Internal Server Error"}})

(def config
  (meta-merge config/defaults
              config/environ
              prod-config))

(defn -main [& args]
  (let [system (new-system config)]
    (println "Starting HTTP server on port" (-> system :http :port))
    (add-shutdown-hook ::stop-system #(component/stop system))
    (component/start system)))
