(ns back-channeling.database.datomic
  (:require [integrant.core :as ig]
            [datomic.api :as d]))

(defrecord Boundary [connection])

(defmethod ig/init-key :back-channeling.database/datomic [_ {:keys [uri]}]
    (let [create? (d/create-database uri)]
      (println "reload")
      (->Boundary (d/connect uri))))
