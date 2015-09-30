(ns back-channeling.resources
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clj-time.format :as time-fmt]
            [clj-time.coerce :refer [from-date to-date]]
            [compojure.core :refer [defroutes ANY]]
            [liberator.representation :refer [ring-response]]
            [liberator.core :refer [defresource]]
            [datomic.api :as d]
            [bouncer.core :as b]
            [bouncer.validators :as v]
            (back-channeling [model :as model]
                             [server :as server]
                             [token :as token]))
  (:import [java.util Date UUID]
           [java.nio.file Files Paths CopyOption]
           [java.nio.file.attribute FileAttribute]))

(def iso8601-formatter (time-fmt/formatters :basic-date-time))
(extend-type java.util.Date
  json/JSONWriter
  (-write [date out]
    (json/-write (time-fmt/unparse iso8601-formatter (from-date date)) out)))
(extend-type java.util.UUID
  json/JSONWriter
  (-write [uuid out]
    (json/-write (.toString uuid) out)))

(defn- body-as-string [ctx]
  (if-let [body (get-in ctx [:request :body])]
    (condp instance? body
      java.lang.String body
      (slurp (io/reader body)))))

(defn- validate [model validation-spec]
  (if validation-spec
    (let [[result map] (b/validate model validation-spec)]
      (if result
        {:message (pr-str (:bouncer.core/errors map))}
        [false {:edn model}]))
    [false {:edn model}]))

(defn- parse-request
  ([context]
   (parse-request context nil))
  ([context validation-spec]
   (when (#{:put :post} (get-in context [:request :request-method]))
     (try
       (if-let [body (body-as-string context)]
         (case (get-in context [:request :content-type])
           "application/edn"  (validate (edn/read-string body) validation-spec)
           "application/json" (validate (json/read-str body :key-fn keyword) validation-spec)
           {:message "Unknown format."})
         false)
       (catch Exception e
         (log/error e "fail to parse edn.")
         {:message (format "IOException: %s" (.getMessage e))})))))

(defresource boards-resource
  :available-media-types ["application/edn" "application/json"]
  :allowed-methods [:get]
  :handle-ok (fn [_]
               (model/query '{:find [[(pull ?board [:*]) ...]]
                              :where [[?board :board/name]]})))

(defresource board-resource [board-name]
  :available-media-types ["application/edn" "application/json"]
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
  :available-media-types ["application/edn" "application/json"]
  :allowed-methods [:get :post]
  :malformed? #(parse-request % {:thread/title [[v/required]
                                                [v/max-count 255]]
                                 :comment/content [[v/required]
                                                   [v/max-count 4000]]})
  :handle-created (fn [ctx]
             {:db/id (:db/id ctx)})
  :post! (fn [{th :edn req :request}]
           (let [user (model/query '{:find [?u .]
                                     :in [$ ?name]
                                     :where [[?u :user/name ?name]]}
                                   (get-in req [:identity :user/name]))
                 now (Date.)
                 thread-id (d/tempid :db.part/user)
                 tempids (-> (model/transact [[:db/add [:board/name board-name] :board/threads thread-id]
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
  :available-media-types ["application/edn" "application/json"]
  :allowed-methods [:get :put]
  :malformed? #(parse-request %)
  :put! (fn [{{:keys [add-watcher remove-watcher]} :edn req :request}]
          (when-let [user (model/query '{:find [?u .] :in [$ ?name] :where [[?u :user/name ?name]]}
                                       (get-in req [:identity :user/name]))]
            (when add-watcher
              (model/transact [[:db/add thread-id :thread/watchers user]]))
            (when remove-watcher
              (model/transact [[:db/retract thread-id :thread/watchers user]]))))
  :handle-created (fn [_]
                    {:status "ok"})
  :handle-ok (fn [_]
               (-> (model/pull '[:*
                                 {:thread/comments
                                  [:*
                                   {:comment/format [:db/ident]}
                                   {:comment/posted-by [:user/name :user/email]}]}] thread-id)
                   (update-in [:thread/comments] (partial map-indexed #(assoc %2  :comment/no (inc %1)))))))

(defresource comments-resource [thread-id from to]
  :available-media-types ["application/edn" "application/json"]
  :allowed-methods [:get :post]
  :malformed? #(parse-request % {:comment/content [[v/required]
                                                   [v/max-count 4000]]})
  :processable? (fn [ctx]
                  (if (#{:put :post} (get-in ctx [:request :request-method]))
                    (let [resnum (model/query '{:find [(count ?comment) .]
                                              :in [$ ?thread]
                                                :where [[?thread :thread/comments ?comment]]} thread-id)]
                      (if (< resnum 1000) {:thread/resnum resnum} false))
                    true))
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

(defresource voices-resource [thread-id]
  :available-media-types ["application/edn" "application-json"]
  :allowed-methods [:post]
  :malformed? (fn [ctx]
                (let [content-type (get-in ctx [:request :headers "content-type"])]
                  (case content-type
                    "audio/ogg" [false {::media-type :audio/ogg}]
                    "audio/wav" [false {::media-type :audio/wav}]
                    true)))
  :post! (fn [ctx]
           (let [body-stream (get-in ctx [:request :body])
                 filename (str (.toString (UUID/randomUUID))
                              (case (::media-type ctx)
                                :audio/ogg ".ogg"
                                :audio/wav ".wav")) 
                 path (Paths/get "voices"
                                 (into-array String [(str thread-id) filename]))]
             (Files/createDirectories (.getParent path)
                                      (make-array FileAttribute 0))
             (Files/copy body-stream path
                         (make-array CopyOption 0))
             {::filename filename}))
  :handle-created (fn [ctx]
                    {:comment/content (str thread-id "/" (::filename ctx))}))

(defresource users-resource [path]
  :available-media-types ["application/edn" "application/json"]
  :allowed-methods [:get]
  :handle-ok (fn [_]
               (vec (server/find-users path))))

(defresource articles-resource
  :available-media-types ["application/edn" "application/json"]
  :allowed-methods [:get :post]
  :malformed? #(parse-request %)
  :post! (fn [{article :edn req :request}]
           (let [article-id (d/tempid :db.part/user)
                 tempids (-> (model/transact
                              (apply concat [{:db/id article-id
                                              :article/name (:article/name article)
                                              :article/curator [:user/name (get-in article [:article/curator :user/name])]}]
                                     (for [block (:article/blocks article)]
                                       (let [tempid (d/tempid :db.part/user)]
                                         [[:db/add article-id :article/blocks tempid]
                                          {:db/id tempid
                                           :curating-block/content (:curating-block/content block)
                                           :curating-block/format  (:curating-block/format  block)
                                           :curating-block/posted-at (:curating-block/posted-at block)
                                           :curating-block/posted-by [:user/name (get-in block [:curating-block/posted-by :user/name])]}]))))
                             :tempids)]
             {:db/id (model/resolve-tempid tempids article-id)}))
  :handle-created (fn [ctx]
                    {:db/id (:db/id ctx)})
  :handle-ok (fn [_]
               (model/query '{:find [[(pull ?a [:*]) ...]]
                              :where [[?a :article/name]]})))

(defresource article-resource [article-id]
  :available-media-types ["application/edn" "application/json"]
  :allowed-methods [:get :put :delete]
  :malformed? #(parse-request %)
  :put! (fn [{article :edn req :request}]
          (let [retract-transaction (->> (:article/blocks (model/pull '[:article/blocks] article-id))
                                         (map (fn [{id :db/id}]
                                                [:db/retract article-id :article/blocks id])))]
            (model/transact (apply
                             concat retract-transaction
                             [{:db/id article-id
                               :article/name (:article/name article)
                               :article/curator [:user/name (get-in article [:article/curator :user/name])]}]
                             (for [block (:article/blocks article)]
                               (let [tempid (d/tempid :db.part/user)]
                                 [[:db/add article-id :article/blocks tempid]
                                  {:db/id tempid
                                   :curating-block/content (:curating-block/content block)
                                   :curating-block/format  (:curating-block/format  block)
                                   :curating-block/posted-at (:curating-block/posted-at block)
                                   :curating-block/posted-by [:user/name (get-in block [:curating-block/posted-by :user/name])]}]))))))
  :handle-ok (fn [_]
               (model/pull '[:*
                             {:article/curator [:user/name :user/email]}
                             {:artpicle/blocks [:curating-block/posted-at
                                               :curating-block/content
                                               {:curating-block/format [:db/ident]}
                                               {:curating-block/posted-by [:user/name :user/email]}]}]
                           article-id)))

(defresource token-resource
  :available-media-types ["application/edn" "application/json"]
  :allowed-methods [:post]
  :malformed? (fn [ctx]
                (if-let [identity (get-in ctx [:request :identity])]
                  [false {::identity identity}]
                  (if-let [code (get-in ctx [:request :params :code])]
                    (if-let [identity (model/query '{:find [(pull ?s [:user/name :user/email]) .]
                                                     :in [$ ?token]
                                                     :where [[?s :user/token ?token]]} code)]
                      [false {::identity identity}] 
                      {:message "code is invalid."})
                    {:message "code is required."})))

  :post! (fn [{identity ::identity}]
           (let [token (token/new-token identity)]
             {::post-response (merge identity
                                     {:token-type "bearer"
                                      :access-token token})}))
  :handle-created (fn [ctx]
                    (::post-response ctx)))

(defroutes api-routes
  (ANY "/token" [] token-resource)
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
  (ANY "/thread/:thread-id/voices" [thread-id] (voices-resource (Long/parseLong thread-id)))
  (ANY "/articles" [] articles-resource)
  (ANY "/article/:article-id" [article-id]
    (article-resource (Long/parseLong article-id)))
  (ANY "/users" [] (users-resource "/ws")))

