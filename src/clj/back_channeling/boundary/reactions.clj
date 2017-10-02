(ns back-channeling.boundary.reactions
  (:require [datomic.api :as d]
            [back-channeling.database.datomic]))

(defprotocol Reactions
  (find-all [datomic])
  (find-by-name [datomic reaction-name]))

(extend-protocol Reactions
  back_channeling.database.datomic.Boundary
  (find-all [{:keys [connection]}]
    (d/q '{:find [[(pull ?r [:*]) ...]]
           :in [$]
           :where [[?r :reaction/name]]}
         (d/db connection)))

  (find-by-name [{:keys [connection]} reaction-name]
    (d/q '{:find [?r .]
           :in [$ ?r-name]
           :where [[?r :reaction/name ?r-name]]}
         (d/db connection)
         reaction-name)))
