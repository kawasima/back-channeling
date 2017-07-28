(ns back-channeling.routing
  (:require [om.core :as om :include-macros true]
            [clojure.browser.net :as net]
            [secretary.core :as sec :include-macros true]
            [goog.events :as events]
            [goog.History]
            [goog.history.EventType :as HistoryEventType]
            [goog.net.EventType :as EventType]
            [back-channeling.api :as api])
  (:use [cljs.reader :only [read-string]])
  (:import [goog.History]))

(defn fetch-boards [app]
  (api/request "/api/boards"
               {:handler (fn [response]
                           (let [boards (->> response
                                             (map #(update-in % [:board/threads] (fn [threads]
                                                        (when threads
                                                          (->> threads
                                                               (map (fn [t] {(:db/id t) t}))
                                                               (reduce merge {}))))))
                                             (reduce #(assoc %1 (:board/name %2) {:value %2}) {}))]
                             (om/transact! app #(assoc % :boards boards :page :boards))))}))

(defn fetch-board [board-name app]
  (api/request (str "/api/board/" board-name)
               {:handler (fn [response]
                           (let [new-board (update-in response [:board/threads]
                                                      (fn [threads]
                                                        (->> threads
                                                             (map (fn [t] {(:db/id t) t}))
                                                             (reduce merge {}))))]
                             (if (get-in @app [:boards board-name])
                               (om/transact! app
                                             (fn [app]
                                               (-> app
                                                   (update-in [:boards board-name :value :board/threads]
                                                     #(merge-with merge % (:board/threads new-board)))
                                                   (assoc :page :board :target-board-name board-name)
                                                   (assoc-in [:boards board-name :target :thread] 0)
                                                   (assoc-in [:boards board-name :target :comment] nil))))
                               (om/transact! app
                                             #(-> %
                                                  (assoc-in [:boards board-name :value] new-board)
                                                  (assoc :page :board :target-board-name board-name)
                                                  (assoc-in [:boards board-name :target :thread] 0)
                                                  (assoc-in [:boards board-name :target :comment] nil))))))}))

(defn fetch-thread [thread-id comment-no board-name app]
  (when (> thread-id 0)
    (let [from (inc (count (get-in @app [:boards board-name :value :board/threads thread-id :thread/comments] [])))]
      (api/request (str "/api/thread/" thread-id "/comments/" from "-")
                   {:handler (fn [response]
                               (om/transact! app
                                             #(-> %
                                                  (update-in [:boards board-name :value :board/threads thread-id :thread/comments]
                                                             (fn [comments new-comments]
                                                               (vec (concat comments new-comments))) response)
                                                  (assoc :page :board)
                                                  (assoc-in [:boards board-name :target :thread] thread-id)
                                                  (assoc-in [:boards board-name :target :comment] comment-no))))}))))

(defn fetch-articles [app]
  (api/request (str "/api/articles")
               {:handler (fn [response]
                           (om/transact! app
                                         #(assoc % :page :article :articles response)))}))

(defn fetch-article [id app]
  (api/request (str "/api/article/" id)
               {:handler (fn [response]
                           (om/transact! app
                                         #(assoc %
                                                 :page :article
                                                 :target-thread (js/parseInt (get-in response [:article/thread :db/id]))
                                                 :article response)))}))

(defn- setup-routing [app]
  (sec/set-config! :prefix "#")
  (sec/defroute "/" []
    (fetch-boards app))
  (sec/defroute "/board/:board-name" [board-name]
    (fetch-board board-name app))
  (sec/defroute "/board/:board-name/:thread-id" [board-name thread-id]
    (fetch-thread (js/parseInt thread-id) nil board-name app))
  (sec/defroute "/board/:board-name/:thread-id/:comment-no" [board-name thread-id comment-no]
    (fetch-thread (js/parseInt thread-id) comment-no board-name app))
  (sec/defroute "/articles/new" [query-params]
    (om/transact! app #(assoc %
                              :page :article
                              :target-thread (js/parseInt (:thread-id query-params))
                              :article {:article/name nil :article/blocks []})))
  (sec/defroute "/articles" []
    (fetch-articles app))
  (sec/defroute #"/article/(\d+)" [id]
    (fetch-article id app)))

(defn- setup-history [owner]
  (let [history (goog.History.)
        navigation HistoryEventType/NAVIGATE]
    (events/listen history
                   navigation
                   #(-> % .-token sec/dispatch!))
    (.setEnabled history true)))

(defn init [app-state owner]
  (setup-routing app-state)
  (setup-history owner))
