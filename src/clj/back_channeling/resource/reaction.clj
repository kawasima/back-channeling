(ns back-channeling.resource.reaction
  (:require [liberator.core :as liberator]
            (back-channeling.boundary [reactions :as reactions])
            (back-channeling.resource [base :refer [base-resource]])))

(defn reactions-resource [{:keys [datomic]}]
  (liberator/resource
   base-resource
   :allowed-methods [:get]
   :handle-ok (fn [_]
                (println datomic)
                (reactions/find-all datomic))))
