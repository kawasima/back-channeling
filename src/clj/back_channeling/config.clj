(ns back-channeling.config
  (:require [environ.core :refer [env]]))

(def defaults
  {:http      {:port 3009}
   :app       {:same-origin {:access-control-allow-origin "http://localhost:3000"}}
   :socketapp {:path "/ws"}
   :datomic   {:uri  "datomic:mem://bc"
               :recreate? false}})

(def environ {:http {:port (some-> env :port Integer.)}
              :app {:same-origin {:access-control-allow-origin (:access-control-allow-origin env)}}
              :datomic {:uri (some-> env :datomic-url) }})
