(ns back-channeling.boundary.articles
  (:require [datomic.api :as d]
            [back-channeling.database.datomic]))

(defprotocol Articles
  (find-by-id [datomic id])
  (find-by-name [datomic article-name])
  (find-all [datomic])
  (find-blocks [datomic id])
  (save
    [datomic article]
    [datomic article retract-transaction]))

(extend-protocol Articles
  back_channeling.database.datomic.Boundary
  (find-by-id [{:keys [connection]} id]
    (d/pull (d/db connection)
            '[:*
              {:article/curator [:user/name :user/email]}
              {:article/blocks [:curating-block/posted-at
                                :curating-block/content
                                {:curating-block/format [:db/ident]}
                                {:curating-block/posted-by [:user/name :user/email]}]}
              {:article/thread [:*]}]
            id))

  (find-by-name [{:keys [connection]} article-name]
    (d/q '{:find [[?article]]
           :in [$ ?article-name]
           :where [[?article :article/name ?article-name]]}
         (d/db connection) article-name))

  (find-all [{:keys [connection]}]
    (d/q '{:find [[(pull ?a [:*]) ...]]
           :where [[?a :article/name]]}
         (d/db connection)))

  (find-blocks [{:keys [connection]} id]
    (d/pull (d/db connection) '[:article/blocks] id))

  (save [{:keys [connenction]} article]
    (let [id (d/tempid :db.part/user)
          tempids (-> (d/transact
                       connection
                       (apply concat [{:db/id id
                                       :article/name (:article/name article)
                                       :article/curator [:user/name (get-in article [:article/curator :user/name])]
                                       :article/thread (:article/thread article)}]
                              (for [block (:article/blocks article)]
                                (let [tempid (d/tempid :db.part/user)]
                                  [[:db/add id :article/blocks tempid]
                                   {:db/id tempid
                                    :curating-block/content (:curating-block/content block)
                                    :curating-block/format  (:curating-block/format  block)
                                    :curating-block/posted-at (:curating-block/posted-at block)
                                    :curating-block/posted-by [:user/name (get-in block [:curating-block/posted-by :user/name])]}]))))
                      deref
                      :tempids)]
      (d/resolve-tempid (d/db connection) tempids article-id)))

  (save [{:keys [connection]} article retract-transaction]
    (-> (d/transact
         connection
         (apply
          concat retract-transaction
          [{:db/id (:db/id article)
            :article/name (:article/name article)
            :article/curator [:user/name (get-in article [:article/curator :user/name])]}]
          (for [block (:article/blocks article)]
            (let [tempid (d/tempid :db.part/user)]
              [[:db/add (:db/id article) :article/blocks tempid]
               {:db/id tempid
                :curating-block/content (:curating-block/content block)
                :curating-block/format  (:curating-block/format  block)
                :curating-block/posted-at (:curating-block/posted-at block)
                :curating-block/posted-by [:user/name (get-in block [:curating-block/posted-by :user/name])]}]))))
        deref)))
