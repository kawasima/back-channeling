(ns back-channeling.update
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [cljs.core.async :refer [put! <! chan timeout]]
            [medley.core :as m]
            [back-channeling.api :as api]
            [back-channeling.routing :as routing]
            [back-channeling.notification :as notification]
            [back-channeling.socket :as socket])
  (:use [cljs.reader :only [read-string]]))

(defn refresh-board [app board-name]
  (api/request (str "/api/board/" board-name)
               {:handler (fn [response]
                           (om/update! app [:board] response))}))

(defn fetch-comments
  ([msgbox thread]
   (fetch-comments msgbox thread 1 nil))
  ([msgbox thread from]
   (fetch-comments msgbox thread from nil))
  ([msgbox {:keys [board/name db/id] :as thread} from to]
   (api/request
    (str "/api/board/" name "/thread/" id "/comments/" from "-" to)
    {:handler (fn [fetched-comments]
                (if (= from to)
                  (put! msgbox [:refresh-comment
                                {:thread thread
                                 :comment/no from
                                 :comment (first fetched-comments)}])
                  (put! msgbox [:add-comments
                                {:thread thread
                                 :comment/from from
                                 :comment/to to
                                 :comments fetched-comments}])))})))

(defn find-thread [threads id]
  (->> (map-indexed vector threads)
       (filter #(= (:db/id (second %)) id))
       (map first)
       first))

(defn refresh-thread [app msgbox thread]
  (when (= (:board/name thread) (get-in @app [:board :board/name]))
    (om/transact! app [:board :board/threads]
                  #(update-in % [(find-thread % (:db/id thread))]
                              assoc
                              :thread/last-updated (:thread/last-updated thread)
                              :thread/resnum (:thread/resnum thread)))
    (when (get-in @app [:threads (:db/id thread) :thread/active?])
      (fetch-comments msgbox thread
                      (or (:comments/from thread)
                          (inc (count (get-in @app [:threads (:db/id thread) :thread/comments]))))
                      (:comments/to thread)))))

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

(defn init [ch app]
  (api/request "/api/token" :POST
                     {:handler
                      (fn [response]
                        (connect-socket app ch (:access-token response)))
                      :error-handler
                      (fn [response error-code]
                        (.error js/console "Can't connect websocket (;;)")
                        #_(set! (.. js/document -location -href) "/"))})
  (go-loop []
    (let [[msg body] (<! ch)]
      (case msg
            :log
            (let [{:keys [message]} body]
              (println (str "MsgBox log : " message)))

            :refresh-comment
            (let [{:keys [comment/no comment] {id :db/id} :thread} body]
              (om/transact! app [:threads id :thread/comments]
                            (fn [comments]
                              (map #(if (= (:db/id comment) (:db/id %)) comment %) comments))))

            :add-comments
            (let [{:keys [comment/from comment/to comments] {id :db/id} :thread} body
                  readnum (-> comments last (:comment/no 0))]
              (om/transact! app (fn [app]
                                  (-> app
                                      (assoc :page :board)
                                      (update-in [:threads id :thread/comments] #(concat % comments))
                                      (update-in [:board :board/threads]
                                                 #(update-in % [(find-thread % id)]
                                                             (fn [thread] (if (> (:thread/readnum thread) readnum)
                                                                            thread
                                                                            (assoc thread :thread/readnum readnum)))))))))

            :move-to-thread
            (let [{thread-id :db/id board-name :board/name :as thread} body
                  page (:page @app)
                  from (-> (get-in @app [:threads thread-id :thread/comments]) count inc)]
              (when (= page :initializing) (refresh-board app board-name))
              (om/transact! app #(-> %
                                     (update :threads (fn [ths]
                                                        (m/map-vals
                                                          (fn [th] (assoc th :thread/active? false))
                                                          ths)))
                                     (assoc-in [:threads thread-id :thread/active?] true)
                                     (assoc-in [:threads thread-id :db/id] thread-id)
                                     (assoc-in [:threads thread-id :thread/title]
                                               (let [threads (get-in app [:board :board/threads])]
                                                 (get-in app [:board :board/threads (find-thread threads thread-id) :thread/title])))
                                     (assoc-in [:threads thread-id :thread/last-comment-no] from)))
              (fetch-comments ch thread from nil))

            (println "Unknown Msg"))
      (recur)))
  (routing/init app ch))
