(ns back-channeling.boundary.threads
  (:require [datomic.api :as d]
            [back-channeling.database.datomic])
  (:import [java.util Date]))

(defprotocol Threads
  (pull [datomic id])
  (find-threads [datomic board-name q])
  (find-thread  [datomic thread-id])
  (find-watchers [datomic thread-id])

  (add-watcher    [datomic thread-id identity])
  (remove-watcher [datomic thread-id identity])
  (open-thread    [datomic thread-id])
  (close-thread   [datomic thread-id])
  (save           [datomic board-name th user]))

(extend-protocol Threads
  back_channeling.database.datomic.Boundary
  (pull [{:keys [connection]} id]
    (d/pull (d/db connection)
            '[:db/id :thread/title :thread/since :thread/last-updated
              :thread/public?
              {:thread/watchers [:user/name]}]
            id))

  (find-threads [{:keys [connection]} board-name q]
    (->> (d/q '{:find [?board-name ?thread ?comment ?score]
                :in [$ ?board-name ?search]
                :where [[?board :board/name ?board-name]
                        [?board :board/threads ?thread]
                        [?thread :thread/comments ?comment]
                        [(fulltext $ :comment/content ?search)
                         [[?comment ?content ?tx ?score]]]]}
              (d/db connection) board-name q)
         (map (fn [[board-name thread-id comment-id score]]
                (let [thread (d/pull (d/db connection)
                                     '[:db/id :thread/title
                                       {:thread/watchers [:user/name]}]
                                     thread-id)
                      comment (d/pull (d/db connection) '[:comment/content] comment-id)]
                  (merge thread comment
                         {:score/value score}
                         {:board/name board-name}))))
         (group-by :db/id)
         (map (fn [[k v]]
                (apply max-key :score/value v)))
         (sort-by :score/value >)
         vec))

  (find-thread [{:keys [connection]} thread-id]
    (-> (d/pull (d/db connection)
                '[:*
                  {:thread/comments
                   [:*
                    {:comment/format [:db/ident]}
                    {:comment/posted-by [:user/name :user/email]}]}]
                thread-id)
        (update-in [:thread/comments]
                   (partial map-indexed #(assoc %2  :comment/no (inc %1))))))

  (find-watchers [{:keys [connection]} thread-id]
    (d/pull (d/db connection)
            '[{:thread/watchers
               [:user/name :user/email]}]
            thread-id))

  (save [{:keys [connection]} board-name th user]
    (let [now (Date.)
          thread-id (d/tempid :db.part/user)
          tempids (-> (d/transact
                       connection
                       [[:db/add [:board/name board-name]
                         :board/threads thread-id]
                        {:db/id thread-id
                         :thread/title (:thread/title th)
                         :thread/since now
                         :thread/last-updated now
                         :thread/public? (get th :thread/public? true)}
                        [:db/add thread-id :thread/comments #db/id[:db.part/user -2]]
                        {:db/id #db/id[:db.part/user -2]
                         :comment/posted-at now
                         :comment/posted-by user
                         :comment/format (get th :comment/format :comment.format/plain)
                         :comment/content (:comment/content th)}])
                      deref
                      :tempids)]
      [tempids thread-id]))

  (add-watcher [{:keys [connection]} th identity]
    (-> (d/transact connection
                    [[:db/add th
                      :thread/watchers [:user/name (:user/name identity)]]])
        deref))

  (remove-watcher [{:keys [connection]} th identity]
    (-> (d/transact connection
                    [[:db/retract th
                      :thread/watchers [:user/name (:user/name identity)]]])
        deref))

  (open-thread [{:keys [connection]} th]
    (-> (d/transact connection
                    [[:db/add th
                      :thread/public? true]])
        deref))

  (close-thread [{:keys [connection]} th]
    (-> (d/transact connection
                    [[:db/add th
                      :thread/public? false]])
        deref)))
