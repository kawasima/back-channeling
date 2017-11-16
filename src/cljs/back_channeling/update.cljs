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

(defn connect-socket [app msgbox token]
  (socket/open (str (if (= "https:" (.-protocol js/location)) "wss://" "ws://")
                    (.-host js/location)
                    (some-> js/document
                            (.querySelector "meta[property='bc:prefix']")
                            (.getAttribute "content"))
                    "/ws?token=" token)
               :on-message (fn [message]
                             (let [[cmd data] (read-string message)]
                               (case cmd
                                 :notify (notification/show data)
                                 :update-board (refresh-board app (:board/name data))
                                 :update-thread (refresh-thread app msgbox data)
                                 :join  (om/transact! app [:users] #(conj % data))
                                 :leave (om/transact! app [:users] #(disj % data))
                                 :call  (js/alert (:message data)))))))

(defmulti update-app (fn [key body ch app] key))

(defmethod update-app :default [key body ch app]
  (println (str "Unknown key:" key)))

(defmethod update-app :log [_ {:keys [message]} ch app]
  (println (str "MsgBox log:" message)))

(defmethod update-app :refresh-comment [_ {:keys [comment/no comment] {id :db/id} :thread} ch app]
  (om/transact! app [:threads id :thread/comments]
                (fn [comments]
                  (map #(if (= (:db/id comment) (:db/id %)) comment %) comments))))

(defmethod update-app :add-comments [_ {:keys [comment/from comment/to comments] {id :db/id} :thread} ch app]
  (let [readnum (-> comments last (:comment/no 0))]
    (om/transact! app (fn [app]
                        (-> app
                            (assoc :page {:type :board :thread/id id})
                            (assoc-in [:threads id :db/id] id)
                            (update-in [:threads id :thread/comments] #(concat % comments))
                            (update-in [:board :board/threads]
                                       #(update-in % [(find-thread % id)]
                                                   (fn [thread] (if (> (:thread/readnum thread) readnum)
                                                                  thread
                                                                  (assoc thread :thread/readnum readnum))))))))))

(defmethod update-app :move-to-thread [_ {thread-id :db/id board-name :board/name :as thread} ch app]
  (let [page (get-in @app [:page :type])
        from (-> (get-in @app [:threads thread-id :thread/comments]) count inc)]
    (when (= page :initializing) (refresh-board app board-name))
    (om/transact! app #(assoc % :page {:type :board :thread/id thread-id :loading? true}))
    (fetch-comments thread from nil
                    (fn [fetched-comments]
                      (put! ch [:add-comments
                                {:thread thread
                                 :comment/from from
                                 :comments fetched-comments}])
                      (om/transact! app #(assoc % :page {:type :board :thread/id thread-id}))))))

(defmethod update-app :remove-thread [_ {thread-id :thread/id board-name :board/name} ch app]
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
      (om/transact! app #(assoc % :page {:type :board}
                                  :board response)))}))

(defmethod update-app :move-to-board [_ {board-name :board/name} ch app]
  (om/transact! app (fn [app]
                          (-> (if (= board-name (get-in app [:board :board/name]))
                                app
                                (assoc app :threads {}))
                              (assoc :page {:type :board :loading? true}))))
  (fetch-board board-name app))

(defmethod update-app :delete-comment [_ {board-name :board/name
                                          thread-id :thread/id
                                          comment-no :comment/no} ch app]
  (api/request (str "/api/board/" board-name
                    "/thread/" thread-id
                    "/comment/" comment-no) :DELETE {}))

(defn init [ch app]
  (go-loop []
    (let [[key body] (<! ch)]
      (update-app key body ch app)
      (recur)))

  (api/request "/api/token" :POST
               {:handler
                (fn [response]
                  (connect-socket app ch (:access-token response)))
                :error-handler
                (fn [response error-code]
                  (.error js/console "Can't connect websocket (;;)")
                  #_(set! (.. js/document -location -href) "/"))})

  (when-let [user-name (some-> js/document
                               (.querySelector "meta[property='bc:user:name']")
                               (.getAttribute "content"))]
    (api/request (str "/api/user/" user-name) :GET
                 {:handler #(om/update! app [:identity] %)}))

  (routing/init app ch))
