(ns back-channeling.boundary.read-comments
  (:require [datomic.api :as d]
            [back-channeling.database.datomic]))

(defprotocol ReadComments
  (save [datomic thread-id identity comment-no]))

(extend-protocol ReadComments
  back_channeling.database.datomic.Boundary
  (save [{:keys [connection]} thread-id identity comment-no]
    (let [e (d/q '{:find [(pull ?rc [:*]) .]
                   :in [$ ?th ?uname]
                   :where [[?rc :read-comment/thread ?th]
                           [?rc :read-comment/user ?u]
                           [?u  :user/name ?uname]]}
                 (d/db connection) thread-id (:user/name identity))]
      (->> (d/transact
            connection
            [{:db/id (or (:db/id e) (d/tempid :db.part/user))
              :read-comment/thread thread-id
              :read-comment/user [:user/name (:user/name identity)]
              :read-comment/comment-no (max comment-no
                                            (or (:read-comment/comment-no e) 1))}])
           deref))))
