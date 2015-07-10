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

(defn fetch-thread [thread-id comment-no board-name app]
  (when (> thread-id 0)
    (let [from (inc (count (get-in @app [:boards board-name :board/threads thread-id :thread/comments] [])))]
      (api/request (str "/api/thread/" thread-id "/comments/" from "-")
                   {:handler (fn [response]
                               (om/transact! app
                                             #(-> %
                                                  (update-in [:boards board-name :board/threads thread-id :thread/comments]
                                                             (fn [comments new-comments]
                                                               (vec (concat comments new-comments))) response)
                                                  (assoc :page :board :target-thread thread-id :target-comment comment-no))))}))))
(defn- setup-routing [app]
  (sec/set-config! :prefix "#")
  (sec/defroute "/" []
    (om/transact! app #(assoc % :page :board :target-thread 0 :target-comment nil)))
  (sec/defroute "/board/:board-name/" [board-name]
    (om/transact! app #(assoc % :page :board :target-thread 0 :target-comment nil)))
  (sec/defroute "/board/:board-name/:thread-id" [board-name thread-id]
    (fetch-thread (js/parseInt thread-id) nil board-name app))
  (sec/defroute "/board/:board-name/:thread-id/:comment-no" [board-name thread-id comment-no]
    (fetch-thread (js/parseInt thread-id) comment-no board-name app))
  (sec/defroute "/curation/:board-name/:thread-id" [board-name thread-id]
    (om/transact! app #(assoc %
                              :page :curation
                              :target-thread (js/parseInt thread-id)
                              :curating-blocks []))))
  
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

