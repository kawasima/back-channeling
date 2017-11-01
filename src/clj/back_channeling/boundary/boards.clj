(ns back-channeling.boundary.boards
  (:require [datomic.api :as d]
            [back-channeling.database.datomic]))

(defprotocol Boards
  (find-by-name [datomic board-name])
  (find-all [datomic identity])
  (find-threads [datomic board-id identity])
  (save [datomic board] [datomic board board-id]))

(defn find-readnum [db e user-name]
  (let [res (d/q '{:find [?cn .]
                   :in [$ ?e ?name]
                   :where [[?rc :read-comment/thread ?e]
                           [?rc :read-comment/user ?u]
                           [?rc :read-comment/comment-no ?cn]
                           [?u  :user/name ?name]]}
                 db e user-name)]
    (or res 0)))

(extend-protocol Boards
  back_channeling.database.datomic.Boundary
  (find-by-name [{:keys [connection]} board-name]
    (d/q '{:find [(pull ?board [:*]) .]
           :in [$ ?b-name]
           :where [[?board :board/name ?b-name]]}
         (d/db connection)
         board-name))

  (find-all [{:keys [connection]} identity]
    (d/q '{:find [[(pull ?board [:*]) ...]]
           :in [$ ?permissions]
           :where [[?board :board/name]]}
         (d/db connection)
         (or (:permissions identity) #{})))

  (find-threads [{:keys [connection]} board-id identity]
    (->> (d/q '{:find [?th (count ?c) ?cn]
                :in [$ ?bd ?name]
                :where [[?bd :board/threads ?th]
                        [?th :thread/comments ?c]
                        [(back-channeling.boundary.boards/find-readnum $ ?th ?name) ?cn]]}
              (d/db connection)
              board-id
              (:user/name identity))
         (mapv
          (fn [[th cnt cn]]
            (-> (d/pull (d/db connection) '[:db/id :thread/title :thread/since
                                            :thread/last-updated :thread/public?] th)
                (assoc :thread/resnum cnt)
                (assoc :thread/readnum cn))))))

  (save
    ([{:keys [connection]} board]
     (let [board-id (d/tempid :db.part/user)
           tempids (-> (d/transact connection
                                   [{:db/id board-id
                                     :board/name (:board/name board)
                                     :board/description (:board/description board)}])
                       deref
                       :tempids)]
       (d/resolve-tempid (d/db connection) tempids board-id)))

    ([{:keys [connection]} board board-id]
     @(d/transact connection
                  [{:db/id (:db/id board-id)
                    :board/name (:board/name board)
                    :board/description (:board/description board)}]))))
