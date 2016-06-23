(ns back-channeling.config
  (:require [environ.core :refer [env]]))

(def defaults
  {:http      {:port 3009}
   :socketapp {:path "/ws"}})

(def environ {:http {:port (some-> env :port Integer.)}
              :datomic {:uri "datomic:mem://bc"}})

