(ns back-channeling.resource.reaction
  (:require [liberator.core :as liberator]
            (back-channeling.boundary [reactions :as reactions])))

(defn reactions-resource [{:keys [datomic]}]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:get]
   :handle-ok (fn [_]
                (reactions/find-all datomic))))
