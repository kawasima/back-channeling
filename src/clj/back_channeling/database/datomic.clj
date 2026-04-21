(ns back-channeling.database.datomic
  (:require [integrant.core :as ig]
            [datomic.api :as d]))

(defrecord Boundary [connection])

(defmethod ig/init-key :back-channeling.database/datomic [_ {:keys [uri]}]
  (d/create-database uri)
  (->Boundary (d/connect uri)))
