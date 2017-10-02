(ns back-channeling.resource.comment
  (:require [liberator.core :as liberator]
            [bouncer.validators :as v]
            (back-channeling [util :refer [parse-request]])
            [back-channeling.websocket.socketapp :refer [broadcast-message multicast-message]]
            (back-channeling.boundary [comments :as comments]
                                      [threads :as threads]
                                      [reactions :as reactions]
                                      [users :as users])
            (back-channeling.resource [base :refer [base-resource has-permission?]]))
  (:import [java.util Date]))

(defn comments-resource [{:keys [datomic socketapp]} board-name thread-id from to]
  (liberator/resource
   base-resource
   :allowed-methods [:get :post]
   :malformed? #(parse-request % {:comment/content [[v/required]
                                                    [v/max-count 4000]]
                                  :comment/format  [[v/member [:comment.format/plain
                                                               :comment.format/markdown
                                                               :comment.format/voice
                                                               "comment.format/plain"
                                                               "comment.format/markdown"
                                                               "comment.format/voice"]]]})
   :allowed? #(case (get-in % [:request :request-method])
                :get   (has-permission? % #{:read-thread})
                :post  (has-permission? % #{:write-thread}))

   :processable? (fn [ctx]
                   (if (#{:put :post} (get-in ctx [:request :request-method]))
                     (if-let [resnum (comments/count datomic thread-id)]
                       (if (< resnum 1000) {:thread/resnum resnum} false)
                       false)
                     true))
   :post! (fn [{comment :edn req :request resnum :thread/resnum}]
            (let [user (users/find-by-name datomic (get-in req [:identity :user/name]))
                  now (Date.)]
              (comments/save
               datomic
               (concat [[:db/add thread-id :thread/comments #db/id[:db.part/user -1]]
                        {:db/id #db/id[:db.part/user -1]
                         :comment/posted-at now
                         :comment/posted-by user
                         :comment/format (-> comment
                                             (get :comment/format :comment.format/plain)
                                             keyword)
                         :comment/content (:comment/content comment)}]
                       (when-not (:comment/sage? comment)
                         [{:db/id thread-id :thread/last-updated now}])))
              (broadcast-message
               socketapp
               [:update-thread {:db/id thread-id
                                :thread/last-updated now
                                :thread/resnum (inc resnum)
                                :board/name board-name}])
              (when-let [watchers (not-empty (->> (threads/find-watchers datomic thread-id)
                                                  :thread/watchers
                                                  (apply hash-set)))]
                (multicast-message
                 socketapp
                 [:notify {:thread/id thread-id
                           :board/name board-name
                           :comment/no (inc resnum)
                           :comment/posted-at now
                           :comment/posted-by user
                           :comment/content (:comment/content comment)
                           :comment/format (get :comment/format comment :comment.format/plain)}]
                 watchers))))
   :handle-ok (fn [_]
                (->> (comments/find-by-thread datomic thread-id)
                     :thread/comments
                     (map-indexed #(assoc %2 :comment/no (inc %1)))
                     (drop (dec from))
                     vec))))

(defn comment-resource
  "Returns a resource that react to a comment"
  [{:keys [datomic socketapp]} board-name thread-id comment-no]
  {:pre [(integer? thread-id) (integer? comment-no)]}
  (liberator/resource
   base-resource
   :allowed-methods [:post :put]
   :authorized? #(case (get-in % [:request :request-method])
                   :post   (has-permission? % #{:write-thread})
                   :delete (has-permission? % #{:delete-comment}))
   :malformed? #(parse-request % {:reaction/name [[v/required]]})
   :allowed? true ;; TODO authorization
   :post! (fn [{comment-reaction :edn identity ::identity}]
            (let [user (users/find-by-name datomic (:user/name identity))

                  reaction (reactions/find-by-name datomic (:reaction/name comment-reaction))
]
              (comments/add-reaction datomic reaction thread-id comment-no user)
              (broadcast-message
               socketapp
               [:update-thread {:db/id thread-id
                                :thread/last-updated (Date.)
                                :thread/resnum (comments/count datomic thread-id)
                                :comments/from comment-no
                                :comments/to   comment-no
                                :board/name board-name}])))
   :delete! (fn [_])))
