(ns back-channeling.database.cache
  (:require [integrant.core :as ig]
            [clojure.core.cache :as cache]))

(defrecord Boundary [cache])

(defmethod ig/init-key :back-channeling.database/cache [_ {:keys [ttl]}]
  (->Boundary (atom (cache/ttl-cache-factory {} :ttl (* 30 60 1000)))))
