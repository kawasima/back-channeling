(ns back-channeling.resource.comment
  (:require [liberator.core :as liberator]
            [datomic.api :as d]
            (back-channeling [util :refer [parse-request]]
                             [mention :as mention])
            [back-channeling.websocket.socketapp :refer [broadcast-message multicast-message]]
            (back-channeling.boundary [comments :as comments]
                                      [read-comments :as read-comments]
                                      [threads :as threads]
                                      [reactions :as reactions]
                                      [users :as users])
            (back-channeling.resource [base :refer [base-resource has-permission? thread-allowed?
                                                      perm-read-thread perm-write-thread perm-delete-comment]]))
  (:import [java.util Date]))

(defn- broadcast-thread-update
  [socketapp datomic board-name thread-id & {:keys [comment-no user resnum]}]
  (broadcast-message
   socketapp
   [:update-thread (cond-> {:db/id thread-id
                             :thread/last-updated (Date.)
                             :thread/resnum (or resnum (comments/count datomic thread-id))
                             :board/name board-name}
                     comment-no (assoc :comments/no comment-no)
                     user       (assoc :comment/posted-by user))]))

(defn comments-resource [{:keys [datomic socketapp]} board-name thread-id from to]
  (liberator/resource
   base-resource
   :allowed-methods [:get :post]
   :malformed? #(parse-request % [:map
                                  [:comment/content [:string {:min 1 :max 4000}]]
                                  [:comment/format {:optional true}
                                   [:enum :comment.format/plain
                                          :comment.format/markdown
                                          :comment.format/voice
                                          "comment.format/plain"
                                          "comment.format/markdown"
                                          "comment.format/voice"]]])
   :allowed? #(case (get-in % [:request :request-method])
                :get   (has-permission? % perm-read-thread)
                :post  (has-permission? % perm-write-thread))

   :processable? (fn [ctx]
                   (if (#{:put :post} (get-in ctx [:request :request-method]))
                     (if-let [resnum (comments/count datomic thread-id)]
                       (if (< resnum 1000) {:thread/resnum resnum} false)
                       false)
                     true))
   :post! (fn [{comment :edn req :request resnum :thread/resnum :as ctx}]
            (when (thread-allowed? ctx datomic #{:write-any-thread} thread-id)
              (let [user (users/find-by-name datomic (get-in req [:identity :user/name]))
                    now (Date.)
                    comment-id (d/tempid :db.part/user -1)]
                (comments/save
                 datomic
                 (concat [[:db/add thread-id :thread/comments comment-id]
                          {:db/id comment-id
                           :comment/posted-at now
                           :comment/posted-by user
                           :comment/format (-> comment
                                               (get :comment/format :comment.format/plain)
                                               keyword)
                           :comment/content (:comment/content comment)
                           :comment/public? true}]
                         (when-not (:comment/sage? comment)
                           [{:db/id thread-id :thread/last-updated now}])))
                ;; Add mentioned users as watchers
                (doseq [mentioned-name (mention/extract-mentions (:comment/content comment))]
                  (when (users/find-by-name datomic mentioned-name)
                    (threads/add-watcher datomic thread-id {:user/name mentioned-name})))
                (broadcast-thread-update socketapp datomic board-name thread-id
                                        :resnum (inc resnum) :user user)
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
                   watchers)))))
   :handle-ok
   (fn [{identity :identity :as ctx}]
     (if (thread-allowed? ctx datomic #{:read-any-thread} thread-id)
       (let [comments (->> (comments/find-by-thread datomic thread-id)
                           (map-indexed #(assoc %2 :comment/no (inc %1)))
                           (drop (dec from))
                           (take (- (or to 1000) (dec from)))
                           (map #(if (or (:comment/public? %) (has-permission? ctx #{:read-any-thread})) %
                                     (assoc % :comment/content "(deleted)"
                                              :comment/format {:db/ident :comment.format/plain})))
                           vec)]
         (when-let [comment-no (-> comments last :comment/no)]
           (read-comments/save datomic thread-id identity comment-no))
         comments)
       []))))

(defn comment-resource
  "Returns a resource that react to a comment"
  [{:keys [socketapp] {:keys [connection] :as datomic} :datomic} board-name thread-id comment-no]
  {:pre [(integer? thread-id) (integer? comment-no)]}
  (liberator/resource
   base-resource
   :allowed-methods [:post :delete]
   :malformed? #(parse-request % [:map [:reaction/name [:string {:min 1}]]])
   :allowed? #(case (get-in % [:request :request-method])
                   :post   (has-permission? % perm-write-thread)
                   :delete (has-permission? % perm-delete-comment))
   :post! (fn [{comment-reaction :edn identity :identity :as ctx}]
            (when (thread-allowed? ctx datomic #{:write-any-thread} thread-id)
              (let [user (users/find-by-name datomic (:user/name identity))
                    reaction (reactions/find-by-name datomic (:reaction/name comment-reaction))]
                (comments/add-reaction datomic reaction thread-id comment-no user)
                (broadcast-thread-update socketapp datomic board-name thread-id
                                        :comment-no comment-no))))
   :delete! (fn [{identity :identity :as ctx}]
              (when (or (has-permission? ctx #{:delete-any-comment})
                        (-> (comments/find-by-thread datomic thread-id)
                            (nth (dec comment-no))
                            (#(= (get-in % [:comment/posted-by :user/name]) (:user/name identity)))))
                (comments/hide datomic thread-id comment-no)
                (broadcast-thread-update socketapp datomic board-name thread-id
                                        :comment-no comment-no)))))
