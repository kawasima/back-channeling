(ns back-channeling.boundary.comments
  (:require [datomic.api :as d]
            [back-channeling.database.datomic])
  (:import [java.util Date]))

(defprotocol Comments
  (find-by-thread [datomic thread-id])
  (count [datomic thread-id])
  (save [datomic comment])
  (add-reaction [datomic reaction thread-id comment-no user]))

(extend-protocol Comments
  back_channeling.database.datomic.Boundary
  (find-by-thread [{:keys [connection]} thread-id]
    (d/pull (d/db connection)
            '[{:thread/comments
               [:*
                {:comment/format [:db/ident]
                 :comment/posted-by [:user/name :user/email]
                 :comment/reactions
                 [{:comment-reaction/reaction [:reaction/label]
                   :comment-reaction/reaction-by [:user/name :user/email]}]}]}]
            thread-id))

  (count [{:keys [connection]} thread-id]
    (d/q '{:find [(count ?comment) .]
           :in [$ ?thread]
           :where [[?thread :thread/comments ?comment]]}
         (d/db connection) thread-id))

  (save [{:keys [connection]} comment]
    (-> (d/transact connection comment)
        deref))

  (add-reaction [{:keys [connection]} reaction thread-id comment-no user]
    (let [comments (->> (d/pull (d/db connection)
                                '[{:thread/comments [:db/id]}]
                                thread-id)
                        :thread/comments)
          comment-id (->> comments
                          (drop (dec comment-no))
                          (take 1)
                          first
                          :db/id)
          now (Date.)
          tempid (d/tempid :db.part/user)]
      (->> (d/transact connection
                       [{:db/id tempid
                         :comment-reaction/reaction-at now
                         :comment-reaction/reaction-by user
                         :comment-reaction/reaction reaction}
                        [:db/add comment-id
                         :comment/reactions tempid]])
           deref))))
