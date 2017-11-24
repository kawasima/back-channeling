(ns back-channeling.update
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [cljs.core.async :refer [put! <! chan timeout]]
            [medley.core :as m]
            [back-channeling.api :as api]
            [back-channeling.routing :as routing]
            [back-channeling.notification :as notification]
            [back-channeling.socket :as socket]
            [back-channeling.helper :refer [find-thread]])
  (:use [cljs.reader :only [read-string]]))

(defn refresh-board [app board-name]
  (api/request (str "/api/board/" board-name)
               {:handler (fn [response]
                           (om/update! app [:board] response))}))

(defn fetch-comments
  [{:keys [board/name db/id] :as thread} from to handler]
   (api/request
    (str "/api/board/" name "/thread/" id "/comments/" from "-" to)
    {:handler handler}))

(defn refresh-thread [app msgbox thread]
  (when (= (:board/name thread) (get-in @app [:board :board/name]))
    (om/transact! app [:board :board/threads]
                  #(update-in % [(find-thread % (:db/id thread))]
                              assoc
                              :thread/last-updated (:thread/last-updated thread)
                              :thread/resnum (:thread/resnum thread)))
    (when (= (get-in @app [:page :thread/id]) (:db/id thread))
      (if-let [comment-no (:comments/no thread)]
        (fetch-comments thread comment-no comment-no
                        #(put! msgbox [:refresh-comment
                                       {:thread thread
                                        :comment/no comment-no
                                        :comment (first %)}]))
        (let [from (-> (get-in @app [:threads (:db/id thread) :thread/comments]) count inc)]
          (fetch-comments thread from nil
                          #(put! msgbox [:add-comments
                                         {:thread thread
                                          :comment/from from
                                          :comments %}])))))))

(defn open-socket [app msgbox token]
  (socket/open (str (if (= "https:" (.-protocol js/location)) "wss://" "ws://")
                    (.-host js/location)
                    (some-> js/document
                            (.querySelector "meta[property='bc:prefix']")
                            (.getAttribute "content"))
                    "/ws?token=" token)
               :on-open (fn []
                          (when (= (:socket @app) :disconnect)
                            (when-let [thread-id (get-in @app [:page :thread/id])]
                              (let [from (-> (get-in @app [:threads thread-id :thread/comments]) count inc)
                                    thread {:db/id thread-id :board/name (get-in @app [:page :board/name])}]
                                (fetch-comments thread from nil
                                                #(put! msgbox [:add-comments
                                                               {:thread thread
                                                                :comment/from from
                                                                :comments %}])))))
                          (om/update! app [:socket] :connect))
               :on-close (fn [e]
                           (om/update! app [:socket] :disconnect))
               :on-message (fn [message]
                             (let [[cmd data] (read-string message)]
                               (case cmd
                                 :notify (notification/show data)
                                 :update-board (refresh-board app (:board/name data))
                                 :update-thread (refresh-thread app msgbox data)
                                 :join  (om/transact! app [:users] #(conj % data))
                                 :leave (om/transact! app [:users] #(disj % data))
                                 :call  (js/alert (:message data)))))))

(defn connect-socket [app msgbox]
  (when (= (:socket @app) :disconnect)
    (api/request "/api/token" :POST
                 {:handler
                  (fn [response]
                    (open-socket app msgbox (:access-token response)))
                  :error-handler
                  (fn [response error-code]
                    (.error js/console "Can't connect websocket (;;)"))})))

(defmulti update-app (fn [key body ch app] key))

(defmethod update-app :default [key body ch app]
  (println (str "Unknown key:" key)))

(defmethod update-app :log [_ {:keys [message]} ch app]
  (println (str "MsgBox log:" message)))

(defmethod update-app :refresh-comment [_ {:keys [comment/no comment] {id :db/id} :thread} ch app]
  (om/transact! app [:threads id :thread/comments]
                (fn [comments]
                  (map #(if (= (:db/id comment) (:db/id %)) comment %) comments))))

(defmethod update-app :add-comments [_ {:keys [comment/from comment/to comments]
                                        {:keys [db/id]} :thread} ch app]
  (let [lastnum (-> comments last (:comment/no 0))
        readnum (some-> (get-in @app [:board :board/threads])
                (find-thread id)
                (#(get-in @app [:board :board/threads % :thread/readnum]))
                (max lastnum))]
    (om/transact! app (fn [app]
                        (-> app
                            (assoc-in [:threads id :db/id] id)
                            (update-in [:threads id :thread/comments]
                                       #(->> (concat % comments)
                                             (map (fn [comment] [(:comment/no comment) comment]))
                                             (into (sorted-map))
                                             vals))
                            (update-in [:board :board/threads]
                                       #(if readnum
                                          (assoc-in % [(find-thread % id) :thread/readnum] readnum)
                                          %)))))))

(defmethod update-app :move-to-thread [_ {thread-id :db/id
                                          board-name :board/name
                                          comment-no :comment/no :as thread} ch app]
  (let [page (get-in @app [:page :type])
        from (-> (get-in @app [:threads thread-id :thread/comments]) count inc)]
    (when (= page :initializing) (refresh-board app board-name))
    (om/transact! app #(assoc % :page {:type :board
                                       :thread/id thread-id
                                       :board/name board-name
                                       :comment/no comment-no
                                       :loading? true}))
    (put! ch [:scroll-to-comment {:comment/no comment-no}])
    (fetch-comments thread from nil
                    (fn [fetched-comments]
                      (put! ch [:add-comments
                                {:thread thread
                                 :comment/from from
                                 :comments fetched-comments}])
                      (om/transact! app #(update % :page dissoc :loading?))))))

(defmethod update-app :remove-thread [_ {thread-id :thread/id board-name :board/name :as ooo} ch app]
  (om/transact! app [:threads] #(dissoc % thread-id))
  (if-let [id (-> (:threads @app) first first)]
    (set! (.-href js/location) (str "#/board/" board-name "/" id))
    (set! (.-href js/location) (str "#/board/" board-name))))

(defn fetch-boards [app]
  (api/request
   "/api/boards"
   {:handler
    (fn [response]
      (om/transact! app #(assoc % :page {:type :boards}))
      (om/transact! app #(assoc % :boards response)))}))

(defmethod update-app :move-to-boards [_ _ ch app]
  (om/transact! app #(assoc % :page {:type :loading}))
  (fetch-boards app))

(defn fetch-board [board-name app]
  (api/request
   (str "/api/board/" board-name)
   {:handler
    (fn [response]
      (om/transact! app #(-> (assoc % :board response)
                             (update :page dissoc :loading?))))}))

(defmethod update-app :move-to-board [_ {board-name :board/name} ch app]
  (om/transact! app (fn [app]
                          (-> (if (= board-name (get-in app [:board :board/name]))
                                app
                                (assoc app :threads {}))
                              (assoc :page {:type :board :board/name board-name :loading? true}))))
  (fetch-board board-name app))

(defmethod update-app :delete-comment [_ {board-name :board/name
                                          thread-id :thread/id
                                          comment-no :comment/no} ch app]
  (api/request (str "/api/board/" board-name
                    "/thread/" thread-id
                    "/comment/" comment-no) :DELETE {}))

(defmethod update-app :save-thread [_ {:keys [thread board]} ch app]
  (api/request (str "/api/board/" (:board/name board) "/threads")
               :POST
               thread
               {:handler (fn [response]
                             (let [thread (assoc response :thread/title (:thread/title thread)
                                                          :thread/readnum 1)]
                             (set! (.-href js/location)
                                   (str "#/board/" (:board/name board) "/" (:db/id thread)))))}))

(defmethod update-app :reconnect-socket [_ _ ch app]
  (connect-socket app ch))

(defn scroll-to-comment [comment-no]
  (when-let [comment-dom (.. js/document
                             -body
                             (querySelector (str "[data-comment-no='" comment-no "']")))]
    (when comment-dom
      (.scrollTo js/window 0
                 (- (+ (.-scrollY js/window)
                       (some->> (.getBoundingClientRect comment-dom) (.-top)))
                    200)))))

(defmethod update-app :scroll-to-comment [_ {comment-no :comment/no} ch app]
  (scroll-to-comment comment-no))

(defn init [ch app]
  (go-loop []
    (let [[key body] (<! ch)]
      (update-app key body ch app)
      (recur)))

  (connect-socket app ch)

  (when-let [user-name (some-> js/document
                               (.querySelector "meta[property='bc:user:name']")
                               (.getAttribute "content"))]
    (api/request (str "/api/user/" user-name) :GET
                 {:handler #(om/update! app [:identity] %)}))

  (routing/init app ch))
