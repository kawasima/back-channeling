(ns back-channeling.resources
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            (back-channeling [model :as model]
                             [server :as server]))
  (:use [compojure.core :only [defroutes ANY]]
        [liberator.representation :only [ring-response]]
        [liberator.core :only [defresource]])
  (:import [java.util Date]))

(defn- body-as-string [ctx]
  (if-let [body (get-in ctx [:request :body])]
    (condp instance? body
      java.lang.String body
      (slurp (io/reader body)))))

(defn- parse-edn [context]
  (when (#{:put :post} (get-in context [:request :request-method]))
    (try
      (if-let [body (body-as-string context)]
        (let [data (edn/read-string body)]
          [false {:edn data}])
        false)
      (catch Exception e
        (log/error e "fail to parse edn.")
        {:message (format "IOException: %s" (.getMessage e))}))))


(defresource boards-resource []
  :available-media-types ["application/edn"]
  :allowed-methods [:get]
  :handle-ok (fn [_]
               (model/query '{:find [(pull ?board [:*]) ...]
                              :where [[?board :board/name]]})))

(defresource board-resource [board-name]
  :available-media-types ["application/edn"]
  :allowed-methods [:get]
  :handle-ok (fn [ctx]
               (let [board (model/query '{:find [(pull ?board [:*]) .]
                                          :in [$ ?b-name]
                                          :where [[?board :board/name ?b-name]]} board-name)]
                 (->> (model/query '{:find [?thread (count ?comment)]
                                     :in [$ ?board]
                                     :where [[?board :board/threads ?thread]
                                             [?thread :thread/comments ?comment]]}
                                   (:db/id board))
                      (map #(-> (model/pull '[:db/id :thread/title :thread/since :thread/last-updated
                                              {:thread/watchers [:user/name]}]
                                            (first %))
                                (assoc :thread/resnum (second %))
                                (update-in [:thread/watchers]
                                           (fn [watchers]
                                             (->> watchers
                                                  (map :user/name)
                                                  (apply hash-set))))))
                      ((fn [threads] (assoc board :board/threads threads)))))))

(defresource threads-resource [board-name]
  :available-media-types ["application/edn"]
  :allowed-methods [:get :post]
  :malformed? #(parse-edn %)
  :handle-created (fn [ctx]
             {:db/id (:db/id ctx)})
  :post! (fn [{th :edn req :request}]
           (let [user (model/query '{:find [?u .]
                                     :in [$ ?name]
                                     :where [[?u :user/name ?name]]}
                                   (get-in req [:identity :user/name]))
                 now (Date.)
                 thread-id (d/tempid :db.part/user)
                 tempids (-> (model/transact [[:db/add [:board/name (:board/name th)] :board/threads thread-id]
                                              {:db/id thread-id
                                               :thread/title (:thread/title th)
                                               :thread/since now
                                               :thread/last-updated now}
                                              [:db/add thread-id :thread/comments #db/id[:db.part/user -2]]
                                              {:db/id #db/id[:db.part/user -2]
                                               :comment/posted-at now
                                               :comment/posted-by user
                                               :comment/format (get th :comment/format :comment.format/plain)
                                               :comment/content (:comment/content th)}])
                             :tempids)]
             (server/broadcast-message "/ws" [:update-board {:board/name board-name}])
             
             {:db/id (model/resolve-tempid tempids thread-id)}))
  :handle-ok (fn [{{{:keys [q]} :params} :request}]
               (when q
                 (->> (model/query '{:find [?board-name ?thread ?comment ?score] 
                                 :in [$ ?board-name ?search]
                                 :where [[?board :board/name ?board-name]
                                         [?board :board/threads ?thread]
                                         [?thread :thread/comments ?comment]
                                         [(fulltext $ :comment/content ?search) [[?comment ?content ?tx ?score]]]
                                         ]} board-name q)
                    (map (fn [[board-name thread-id comment-id score]]
                           (let [thread (model/pull '[:db/id :thread/title {:thread/watchers [:user/name]}] thread-id)
                                 comment (model/pull '[:comment/content] comment-id)]
                             (merge thread comment
                                    {:score/value score}
                                    {:board/name board-name}))))
                    (group-by :db/id)
                    (map (fn [[k v]]
                           (apply max-key :score/value v)))
                    (sort-by :score/value >)
                    vec))))

(defresource thread-resource [thread-id]
  :available-media-types ["application/edn"]
  :allowed-methods [:get :put]
  :malformed? #(parse-edn %)
  :put! (fn [{{:keys [add-watcher remove-watcher]} :edn req :request}]
          (when-let [user (model/query '{:find [?u .] :in [$ ?name] :where [[?u :user/name ?name]]}
                                       (get-in req [:identity :user/name]))]
            (when add-watcher
              (model/transact [[:db/add thread-id :thread/watchers user]]))
            (when remove-watcher
              (model/transact [[:db/retract thread-id :thread/watchers user]]))))
  :handle-ok (fn [_]
               (-> (model/pull '[:*
                                 {:thread/comments
                                  [:*
                                   {:comment/format [:db/ident]}
                                   {:comment/posted-by [:user/name :user/email]}]}] thread-id)
                   (update-in [:thread/comments] (partial map-indexed #(assoc %2  :comment/no (inc %1)))))))

(defresource comments-resource [thread-id from to]
  :available-media-types ["application/edn"]
  :allowed-methods [:get :post]
  :malformed? #(parse-edn %)
  :processable? (fn [ctx]
                  (let [resnum (model/query '{:find [(count ?comment) .]
                                              :in [$ ?thread]
                                              :where [[?thread :thread/comments ?comment]]} thread-id)]
                    (if (< resnum 1000) {:thread/resnum resnum} false)))
  :post! (fn [{comment :edn req :request resnum :thread/resnum}]
           (let [user (model/query '{:find [?u .]
                                     :in [$ ?name]
                                     :where [[?u :user/name ?name]]}
                                   (get-in req [:identity :user/name]))
                 now (Date.)]
             (model/transact
              (concat [[:db/add thread-id :thread/comments #db/id[:db.part/user -1]]
                       {:db/id #db/id[:db.part/user -1]
                        :comment/posted-at now
                        :comment/posted-by user
                        :comment/format (get comment :comment/format :comment.format/plain)
                        :comment/content (:comment/content comment)}]
                      (when-not (:comment/sage? comment)
                        [{:db/id thread-id :thread/last-updated now}])))
             (server/broadcast-message
              "/ws"
              [:update-thread {:db/id thread-id
                               :thread/last-updated now
                               :thread/resnum (inc resnum)}])
             (when-let [watchers (not-empty (->> (model/pull '[{:thread/watchers [:user/name :user/email]}] thread-id)
                                                 :thread/watchers
                                                 (apply hash-set)))]
               (server/multicast-message
                "/ws"
                [:notify {:thread/id thread-id
                          :board/name (model/query '{:find [?bname .] :in [$ ?t] :where [[?b :board/name ?bname] [?b :board/threads ?t]]} thread-id)
                          :comment/no (inc resnum)
                          :comment/posted-at now
                          :comment/posted-by (model/pull '[:user/name :user/email] user)
                          :comment/content (:comment/content comment)
                          :comment/format (get :comment/format comment :comment.format/plain)}]
                watchers))))
  :handle-ok (fn [_]
               (->> (model/pull '[{:thread/comments
                                   [:*
                                    {:comment/format [:db/ident]}
                                    {:comment/posted-by [:user/name :user/email]}]}] thread-id)
                    :thread/comments
                    (map-indexed #(assoc %2 :comment/no (inc %1)))
                    (drop (dec from)))))

(defresource users-resource [path]
  :available-media-types ["application/edn"]
  :allowed-methods [:get]
  :handle-ok (fn [_]
               (vec (server/find-users path))))

(defresource curations-resource []
  :available-media-types ["application/edn"]
  :allowed-methods [:get :post]
  :malformed? #(parse-edn %)
  :post! (fn [{curation :edn req :request}]
           (model/transact [{:db/id #db/id[:db.part/user -1]
                             :article/name (:article/name curation)
                             :article/curator (:article/curator curation)}
                            [:db/add #db/id[:db.part/user -2] :article/blocks #db/id[:db.part/user -1]]
                            {:db/id #db/id[:db.part/user -2]}])))

(defresource curation-resource [curation-id]
  :available-media-types ["application/edn"]
  :allowed-methods [:get :put :delete]
  :malformed? #(parse-edn %)
  :handle-ok (fn [_]
               (model/pull '[:*
                             {:article/curator [:user/name :user/email]}
                             {:article/blocks [:curating-block/posted-at
                                               :curating-block/content
                                               {:curating-block/format [:db/ident]}
                                               {:curating-block/posted-by [:user/name :user/email]}]}])))

(defroutes api-routes
  (ANY "/boards" [] boards-resource)
  (ANY "/board/:board-name" [board-name]
    (board-resource board-name))
  (ANY "/board/:board-name/threads" [board-name]
    (threads-resource board-name))
  (ANY "/thread/:thread-id" [thread-id]
    (thread-resource (Long/parseLong thread-id)))
  (ANY "/thread/:thread-id/comments" [thread-id]
    (comments-resource (Long/parseLong thread-id) 1 nil))
  (ANY ["/thread/:thread-id/comments/:comment-range" :comment-range #"\d+\-(\d+)?"] [thread-id comment-range]
    (let [[_ from to] (re-matches #"(\d+)\-(\d+)?" comment-range)]
      (comments-resource (Long/parseLong thread-id)
                         (when from (Long/parseLong from))
                         (when to (Long/parseLong to)))))
  (ANY "/users" [] (users-resource "/ws")))

