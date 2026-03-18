(ns back-channeling.events
  (:require [re-frame.core :as rf]
            [back-channeling.db :as db]
            [back-channeling.api :as api]
            [back-channeling.helper :refer [find-thread find-board]])
  (:use [cljs.reader :only [read-string]]))

(def title "Back Channeling")

;; -- Initialize ------------------------------------------------------------

(defn- read-meta [property]
  (some-> js/document
          (.querySelector (str "meta[property='" property "']"))
          (.getAttribute "content")))

(rf/reg-event-fx
 ::initialize
 (fn [_ _]
   (let [prefix (read-meta "bc:prefix")
         user-name (read-meta "bc:user:name")
         user-email (read-meta "bc:user:email")
         local-user (when user-name
                      {:user/name user-name :user/email user-email})]
     {:db (cond-> (assoc db/default-db :prefix prefix)
            local-user (assoc :local-user local-user))
      :dispatch-n (cond-> [[::-fetch-reactions]
                            [::-fetch-users]
                            [::connect-socket]]
                    user-name (conj [::fetch-identity user-name]))})))

;; -- Identity ---------------------------------------------------------------

(rf/reg-event-fx
 ::fetch-identity
 (fn [_ [_ user-name]]
   {:http {:path (str "/api/user/" user-name)
           :handler (fn [response] [::set-identity response])}}))

(rf/reg-event-db
 ::set-identity
 (fn [db [_ identity]]
   (assoc db :identity identity)))

;; -- Reactions & Users (bootstrap) ------------------------------------------

(rf/reg-event-fx
 ::-fetch-reactions
 (fn [_ _]
   {:http {:path "/api/reactions"
           :handler (fn [response] [::set-reactions response])}}))

(rf/reg-event-db
 ::set-reactions
 (fn [db [_ reactions]]
   (assoc db :reactions reactions)))

(rf/reg-event-fx
 ::-fetch-users
 (fn [_ _]
   {:http {:path "/api/users"
           :handler (fn [response] [::set-users response])}}))

(rf/reg-event-db
 ::set-users
 (fn [db [_ users]]
   (assoc db :users (apply hash-set users))))

;; -- Navigation events ------------------------------------------------------

(rf/reg-event-fx
 ::move-to-boards
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc :page (if (not-empty (:boards db))
                           {:type :boards}
                           {:type :loading}))
            (dissoc :search-highlight))
    :dispatch [::fetch-boards]}))

(rf/reg-event-fx
 ::fetch-boards
 (fn [_ _]
   {:http {:path "/api/boards"
           :handler (fn [response] [::boards-fetched response])}}))

(rf/reg-event-fx
 ::boards-fetched
 (fn [{:keys [db]} [_ boards]]
   (let [new-db (assoc db :page {:type :boards} :boards boards)]
     {:db new-db
      :dispatch-n (mapv (fn [board]
                          [::fetch-board-permissions (:board/name board)])
                        boards)})))

(rf/reg-event-fx
 ::fetch-board-permissions
 (fn [{:keys [db]} [_ board-name]]
   (let [user-name (get-in db [:identity :user/name])]
     (when user-name
       {:http {:path (str "/api/board/" board-name "/user/" user-name)
               :handler (fn [response] [::board-permissions-fetched board-name response])
               :error-handler (fn [_ xhrio]
                                (when (= (.getStatus xhrio) 404)
                                  [::board-permissions-fetched board-name {:user/permissions nil}]))}}))))

(rf/reg-event-db
 ::board-permissions-fetched
 (fn [db [_ board-name {:keys [user/permissions]}]]
   (let [idx (find-board (:boards db) board-name)]
     (if idx
       (assoc-in db [:boards idx :user/permissions] permissions)
       db))))

(rf/reg-event-fx
 ::move-to-board
 (fn [{:keys [db]} [_ {:keys [board/name]}]]
   {:db (-> (if (= name (get-in db [:board :board/name]))
              db
              (assoc db :board {} :threads {} :thread-order []))
            (assoc :page {:type :board :board/name name :loading? true})
            (dissoc :search-highlight))
    :dispatch-n [[::fetch-board name]
                 [::subscribe-board name]]}))

(rf/reg-event-fx
 ::fetch-board
 (fn [_ [_ board-name]]
   {:http {:path (str "/api/board/" board-name)
           :handler (fn [response] [::board-fetched response])}}))

(rf/reg-event-db
 ::board-fetched
 (fn [db [_ board]]
   (-> (assoc db :board board)
       (update :page dissoc :loading?))))

(rf/reg-event-fx
 ::move-to-thread
 (fn [{:keys [db]} [_ {:keys [db/id board/name comment/no search-query] :as thread}]]
   (let [from (-> (get-in db [:threads id :thread/comments]) count inc)
         need-board-refresh? (not= name (get-in db [:board :board/name]))
         current-order (if need-board-refresh? [] (:thread-order db []))
         already-open? (some #{id} current-order)
         new-order (if already-open?
                     current-order
                     (conj current-order id))
         evict-ids (when (> (count new-order) db/max-open-threads)
                     (subvec new-order 0 (- (count new-order) db/max-open-threads)))
         final-order (if evict-ids
                       (subvec new-order (count evict-ids))
                       new-order)]
     (cond-> {:db (cond-> db
                    need-board-refresh? (assoc :threads {})
                    evict-ids (update :threads #(apply dissoc % evict-ids))
                    true (assoc :page {:type :board
                                       :thread/id id
                                       :board/name name
                                       :comment/no no
                                       :loading? true}
                                :thread-order final-order)
                    search-query (assoc :search-highlight search-query)
                    (nil? search-query) (dissoc :search-highlight))
              :dispatch-n [[::fetch-comments {:thread thread :from from
                                              :callback-event ::comments-fetched-for-thread}]
                           [::scroll-to-comment no]]}
       need-board-refresh?
       (update :dispatch-n conj [::fetch-board name])))))

(rf/reg-event-fx
 ::fetch-comments
 (fn [_ [_ {:keys [thread from to callback-event]}]]
   (let [{:keys [board/name db/id]} thread
         range-str (if to (str from "-" to) (str from "-"))]
     {:http {:path (str "/api/board/" name "/thread/" id "/comments/" range-str)
             :handler (fn [response] [callback-event {:thread thread :from from :comments response}])}})))

(rf/reg-event-fx
 ::comments-fetched-for-thread
 (fn [{:keys [db]} [_ {:keys [thread from comments]}]]
   (let [id (:db/id thread)]
     {:db (-> db
              (assoc-in [:threads id :db/id] id)
              (update-in [:threads id :thread/comments]
                         #(->> (concat % comments)
                               (map (fn [c] [(:comment/no c) c]))
                               (into (sorted-map))
                               vals))
              (update :page dissoc :loading?))
      :dispatch [::update-readnum id]})))

(rf/reg-event-db
 ::update-readnum
 (fn [db [_ thread-id]]
   (let [lastnum (-> (get-in db [:threads thread-id :thread/comments]) last :comment/no)
         threads (get-in db [:board :board/threads])
         idx (find-thread threads thread-id)
         readnum (when idx
                   (some-> (get-in threads [idx :thread/readnum])
                           (max (or lastnum 0))))]
     (if (and idx readnum)
       (assoc-in db [:board :board/threads idx :thread/readnum] readnum)
       db))))

;; -- Add comments (from websocket updates) ----------------------------------

(rf/reg-event-db
 ::add-comments
 (fn [db [_ {:keys [comment/from comments] {:keys [db/id]} :thread}]]
   (let [lastnum (-> comments last (:comment/no 0))
         threads (get-in db [:board :board/threads])
         idx (find-thread threads id)
         readnum (when idx
                   (some-> (get-in threads [idx :thread/readnum])
                           (max lastnum)))]
     (-> db
         (assoc-in [:threads id :db/id] id)
         (update-in [:threads id :thread/comments]
                    #(->> (concat % comments)
                          (map (fn [c] [(:comment/no c) c]))
                          (into (sorted-map))
                          vals))
         (cond->
           (and idx readnum)
           (assoc-in [:board :board/threads idx :thread/readnum] readnum))))))

;; -- Refresh comment (single update) ----------------------------------------

(rf/reg-event-db
 ::refresh-comment
 (fn [db [_ {:keys [comment/no comment] {id :db/id} :thread}]]
   (update-in db [:threads id :thread/comments]
              (fn [comments]
                (map #(if (= (:db/id comment) (:db/id %)) comment %) comments)))))

;; -- Remove thread tab ------------------------------------------------------

(rf/reg-event-fx
 ::remove-thread
 (fn [{:keys [db]} [_ {:keys [thread/id board/name]}]]
   (let [new-threads (dissoc (:threads db) id)
         next-id (-> new-threads first first)]
     {:db (-> db
              (update :threads dissoc id)
              (update :thread-order (fn [order] (filterv #(not= % id) order))))
      :navigate (if next-id
                  (str "#/board/" name "/" next-id)
                  (str "#/board/" name))})))

;; -- Save thread ------------------------------------------------------------

(rf/reg-event-fx
 ::save-thread
 (fn [_ [_ {:keys [thread board]}]]
   {:http {:path (str "/api/board/" (:board/name board) "/threads")
           :method :POST
           :body thread
           :handler (fn [response]
                      [::thread-saved {:response response :thread thread :board board}])}}))

(rf/reg-event-fx
 ::thread-saved
 (fn [_ [_ {:keys [response thread board]}]]
   {:navigate (str "#/board/" (:board/name board) "/" (:db/id response))}))

;; -- Delete comment ---------------------------------------------------------

(rf/reg-event-fx
 ::delete-comment
 (fn [_ [_ {:keys [board/name thread/id comment/no]}]]
   {:http {:path (str "/api/board/" name "/thread/" id "/comment/" no)
           :method :DELETE}}))

;; -- Close / Open thread ----------------------------------------------------

(rf/reg-event-fx
 ::close-thread
 (fn [_ [_ {:keys [thread/id board/name]}]]
   {:http {:path (str "/api/board/" name "/thread/" id)
           :method :PUT
           :body {:close-thread id}}}))

(rf/reg-event-fx
 ::open-thread
 (fn [_ [_ {:keys [thread/id board/name]}]]
   {:http {:path (str "/api/board/" name "/thread/" id)
           :method :PUT
           :body {:open-thread id}}}))

;; -- Watch / Unwatch thread -------------------------------------------------

(rf/reg-event-fx
 ::watch-thread
 (fn [_ [_ {:keys [board/name]}]]
   {:dispatch [::refresh-board name]}))

(rf/reg-event-fx
 ::unwatch-thread
 (fn [_ [_ {:keys [board/name]}]]
   {:dispatch [::refresh-board name]}))

;; -- Refresh board ----------------------------------------------------------

(rf/reg-event-fx
 ::refresh-board
 (fn [{:keys [db]} [_ board-name]]
   {:http {:path (str "/api/board/" board-name)
           :handler (fn [response] [::board-fetched response])}}))

;; -- WebSocket --------------------------------------------------------------

(rf/reg-event-fx
 ::connect-socket
 (fn [{:keys [db]} _]
   (when (= (:socket db) :disconnect)
     {:http {:path "/api/token"
             :method :POST
             :handler (fn [response] [::open-socket (:access-token response)])
             :error-handler (fn [_ _]
                              (.error js/console "Can't connect websocket (;;)")
                              nil)}})))

(rf/reg-event-fx
 ::open-socket
 (fn [{:keys [db]} [_ token]]
   (let [prefix (:prefix db)]
     {:db (assoc db :ws-token token)
      :ws-open {:url (str (if (= "https:" (.-protocol js/location)) "wss://" "ws://")
                          (.-host js/location)
                          prefix
                          "/ws")
                :on-open (fn []
                           (rf/dispatch [::socket-opened]))
                :on-close (fn [_]
                            (rf/dispatch [::socket-closed]))
                :on-message (fn [message]
                              (rf/dispatch [::socket-message message]))}})))

(rf/reg-event-fx
 ::socket-opened
 (fn [{:keys [db]} _]
   (let [was-disconnected? (= (:socket db) :disconnect)
         thread-id (get-in db [:page :thread/id])
         board-name (get-in db [:page :board/name])
         dispatches (cond-> []
                      ;; Always send auth on connect
                      true (conj [::send-auth])
                      ;; Subscribe to current board if any
                      board-name (conj [::subscribe-board board-name])
                      ;; Fetch missed comments on reconnect
                      (and was-disconnected? thread-id)
                      (conj [::fetch-comments
                             {:thread {:db/id thread-id :board/name board-name}
                              :from (-> (get-in db [:threads thread-id :thread/comments]) count inc)
                              :callback-event ::add-comments}]))]
     {:db (assoc db :socket :connect)
      :dispatch-n dispatches})))

(rf/reg-event-fx
 ::send-auth
 (fn [{:keys [db]} _]
   (when-let [token (:ws-token db)]
     {:ws-send {:command :auth :message {:token token}}})))

(rf/reg-event-fx
 ::subscribe-board
 (fn [_ [_ board-name]]
   {:ws-send {:command :subscribe-board :message {:board/name board-name}}}))

(rf/reg-event-db
 ::socket-closed
 (fn [db _]
   (assoc db :socket :disconnect)))

(rf/reg-event-fx
 ::socket-message
 (fn [{:keys [db]} [_ raw-message]]
   (let [[cmd data] (read-string raw-message)]
     (case cmd
       :notify       {:notify data}
       :update-board {:dispatch [::refresh-board (:board/name data)]}
       :update-thread {:dispatch [::ws-update-thread data]}
       :join         {:db (update db :users conj data)}
       :leave        {:db (update db :users disj data)}
       :call         (do (js/alert (:message data)) {})
       {}))))

(rf/reg-event-fx
 ::ws-update-thread
 (fn [{:keys [db]} [_ thread]]
   (let [board-name (get-in db [:board :board/name])]
     (when (= (:board/name thread) board-name)
       (let [my-name (get-in db [:identity :user/name])
             thread-id (:db/id thread)
             idx (find-thread (get-in db [:board :board/threads]) thread-id)
             new-db (if idx
                      (-> db
                          (assoc-in [:board :board/threads idx :thread/last-updated] (:thread/last-updated thread))
                          (assoc-in [:board :board/threads idx :thread/resnum] (:thread/resnum thread))
                          (update-in [:board :board/threads idx :thread/writenum]
                                     (fn [wn]
                                       (if (= my-name (get-in thread [:comment/posted-by :user/name]))
                                         (inc (or wn 0))
                                         (or wn 0)))))
                      db)
             viewing-this-thread? (= (get-in db [:page :thread/id]) thread-id)]
         (cond-> {:db new-db}
           viewing-this-thread?
           (assoc :dispatch
                  (if-let [comment-no (:comments/no thread)]
                    [::fetch-single-comment {:thread thread :comment-no comment-no}]
                    [::fetch-comments {:thread thread
                                       :from (-> (get-in db [:threads thread-id :thread/comments]) count inc)
                                       :callback-event ::add-comments}]))))))))

(rf/reg-event-fx
 ::fetch-single-comment
 (fn [_ [_ {:keys [thread comment-no]}]]
   {:http {:path (str "/api/board/" (:board/name thread)
                      "/thread/" (:db/id thread)
                      "/comments/" comment-no "-" comment-no)
           :handler (fn [response]
                      [::refresh-comment {:thread thread
                                          :comment/no comment-no
                                          :comment (first response)}])}}))

;; -- Scroll to comment ------------------------------------------------------

(rf/reg-event-fx
 ::scroll-to-comment
 (fn [_ [_ comment-no]]
   (when comment-no
     {:scroll-to-comment comment-no})))

;; -- Search highlight -------------------------------------------------------

(rf/reg-event-db
 ::set-search-highlight
 (fn [db [_ query]]
   (assoc db :search-highlight query)))

(rf/reg-event-db
 ::clear-search-highlight
 (fn [db _]
   (dissoc db :search-highlight)))

;; -- Focus title on window focus -------------------------------------------

(rf/reg-event-fx
 ::window-focused
 (fn [_ _]
   {:set-title title}))

;; -- Articles ---------------------------------------------------------------

(rf/reg-event-fx
 ::fetch-articles
 (fn [_ _]
   {:http {:path "/api/articles"
           :handler (fn [response] [::articles-fetched response])}}))

(rf/reg-event-db
 ::articles-fetched
 (fn [db [_ articles]]
   (assoc db :page {:type :article} :articles articles)))

(rf/reg-event-fx
 ::fetch-article
 (fn [_ [_ id]]
   {:http {:path (str "/api/article/" id)
           :handler (fn [response] [::article-fetched response])}}))

(rf/reg-event-db
 ::article-fetched
 (fn [db [_ article]]
   (assoc db
          :page {:type :article}
          :target-thread (js/parseInt (get-in article [:article/thread :db/id]))
          :article article)))

(rf/reg-event-db
 ::new-article
 (fn [db [_ thread-id]]
   (assoc db
          :page {:type :article}
          :target-thread (js/parseInt thread-id)
          :article {:article/name nil :article/blocks []})))

;; -- Search threads --------------------------------------------------------

(rf/reg-event-fx
 ::search-threads
 (fn [{:keys [db]} [_ board-name query]]
   {:http {:path (str "/api/board/" board-name "/threads?q=" (js/encodeURIComponent query))
           :handler (fn [response] [::search-results-fetched response])}}))

(rf/reg-event-db
 ::search-results-fetched
 (fn [db [_ results]]
   (assoc db :search-result results)))

(rf/reg-event-db
 ::clear-search-result
 (fn [db _]
   (dissoc db :search-result)))

;; -- Save board ------------------------------------------------------------

(rf/reg-event-fx
 ::save-board
 (fn [_ [_ {:keys [board on-success on-error]}]]
   {:http-raw {:path "/api/boards"
               :method :POST
               :body board
               :handler (fn [_]
                          (when on-success (on-success))
                          (set! (.-href js/location) (str "#/board/" (:board/name board))))
               :error-handler (fn [_ xhrio]
                                (when on-error (on-error xhrio)))}}))

;; -- Save comment ----------------------------------------------------------

(rf/reg-event-fx
 ::save-comment
 (fn [_ [_ {:keys [board-name comment on-success]}]]
   (if (= (:comment/format comment) :comment.format/voice)
     {:http-raw {:path (str "/api/board/" board-name "/thread/" (:thread/id comment) "/voices")
                 :method :POST
                 :body (:comment/content comment)
                 :format (case (.-type (:comment/content comment))
                           "audio/webm" :webm
                           "audio/ogg"  :ogg
                           "audio/wav"  :wav)
                 :handler (fn [response]
                            (rf/dispatch [::post-comment-text
                                          {:board-name board-name
                                           :comment (merge comment response)
                                           :on-success on-success}]))}}
     {:http-raw {:path (str "/api/board/" board-name "/thread/" (:thread/id comment) "/comments")
                 :method :POST
                 :body comment
                 :handler (fn [response] (when on-success (on-success response)))}})))

(rf/reg-event-fx
 ::post-comment-text
 (fn [_ [_ {:keys [board-name comment on-success]}]]
   {:http-raw {:path (str "/api/board/" board-name "/thread/" (:thread/id comment) "/comments")
               :method :POST
               :body comment
               :handler (fn [response] (when on-success (on-success response)))}}))

;; -- Watch / Unwatch thread (API call) -------------------------------------

(rf/reg-event-fx
 ::watch-thread-api
 (fn [_ [_ {:keys [board-name thread user on-done]}]]
   {:http-raw {:path (str "/api/board/" board-name "/thread/" (:db/id thread))
               :method :PUT
               :body {:add-watcher user}
               :handler (fn [_]
                          (rf/dispatch [::watch-thread {:thread thread :board/name board-name}])
                          (when on-done (on-done true)))}}))

(rf/reg-event-fx
 ::unwatch-thread-api
 (fn [_ [_ {:keys [board-name thread user on-done]}]]
   {:http-raw {:path (str "/api/board/" board-name "/thread/" (:db/id thread))
               :method :PUT
               :body {:remove-watcher user}
               :handler (fn [_]
                          (rf/dispatch [::unwatch-thread {:thread thread :board/name board-name}])
                          (when on-done (on-done false)))}}))

;; -- Add reaction to comment -----------------------------------------------

(rf/reg-event-fx
 ::add-reaction
 (fn [_ [_ {:keys [board-name thread-id comment-no reaction on-done]}]]
   {:http {:path (str "/api/board/" board-name "/thread/" thread-id "/comment/" comment-no)
           :method :POST
           :body (select-keys reaction [:reaction/name])
           :handler (fn [_] (when on-done [::reaction-added]) nil)}}))

;; -- Fetch thread (for curation) -------------------------------------------

(rf/reg-event-fx
 ::fetch-thread-comments
 (fn [_ [_ thread-id]]
   {:http {:path (str "/api/thread/" thread-id)
           :handler (fn [response] [::thread-comments-fetched response])}}))

(rf/reg-event-db
 ::thread-comments-fetched
 (fn [db [_ thread]]
   (assoc-in db [:curation-thread :thread/comments] (:thread/comments thread))))

;; -- Save article ----------------------------------------------------------

(rf/reg-event-fx
 ::save-article
 (fn [_ [_ {:keys [article user thread-id on-success on-error]}]]
   (if-let [id (:db/id article)]
     {:http-raw {:path (str "/api/article/" id)
                 :method :PUT
                 :body (assoc article :article/curator user :article/thread thread-id)
                 :handler (fn [_] (when on-success (on-success id)))}}
     {:http-raw {:path "/api/articles"
                 :method :POST
                 :body (assoc article :article/curator user :article/thread thread-id)
                 :handler (fn [response]
                            (when on-success (on-success (:db/id response))))
                 :error-handler (fn [_ xhrio]
                                  (when on-error (on-error xhrio)))}})))
