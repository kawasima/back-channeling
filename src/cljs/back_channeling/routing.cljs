(ns back-channeling.routing
  (:require [om.core :as om :include-macros true]
            [cljs.core.async :refer [put!]]
            [medley.core :as m]
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
  (api/request
   "/api/boards"
   {:handler
    (fn [response]
      (om/transact! app #(assoc % :boards response)))}))

(defn fetch-board [board-name app]
  (api/request
   (str "/api/board/" board-name)
   {:handler
    (fn [response]
      (om/transact! app #(assoc % :board response)))}))

(defn fetch-thread [thread-id board-name app]
  (when (> thread-id 0)
    (let [from (inc (count (get-in @app [:threads thread-id :thread/comments])))]
      (api/request
       (str "/api/board/" board-name "/thread/" thread-id "/comments/" from "-")
       {:handler
        (fn [response]
          (om/transact! app
                        #(-> %
                             (update :threads (fn [ths]
                                                (m/map-vals
                                                  (fn [th] (assoc th :thread/active? false))
                                                  ths)))
                             (assoc-in  [:threads thread-id :db/id] thread-id)
                             (update-in [:threads thread-id :thread/comments] concat response)
                             (assoc-in  [:threads thread-id :thread/active?] true)
                             (assoc-in  [:threads thread-id :thread/last-comment-no] 0)
                             (update-in [:board :board/threads]
                                        (fn [threads]
                                          (->> threads
                                               (map (fn [thread]
                                                  (if (= (:db/id thread) thread-id)
                                                         (assoc thread :thread/readnum (-> response last :comment/no))
                                                         thread)))
                                               vec))))))}))))

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

(defn- setup-routing [app msgbox]
  (let [prefix (some-> js/document
                       (.querySelector "meta[property='bc:prefix']")
                       (.getAttribute "content"))]
    (sec/set-config! :prefix (str prefix "/#")))
  (sec/defroute "/" []
    (om/transact! app #(assoc % :page :boards))
    (fetch-boards app))
  (sec/defroute "/board/:board-name" [board-name]
    (om/transact! app (fn [app]
                        (if (= board-name (get-in app [:board :board/name]))
                          (assoc app :page :board)
                          (assoc app :page :board :threads {} :board {}))))
    (fetch-board board-name app))
  (sec/defroute "/board/:board-name/:thread-id" [board-name thread-id]
    (put! msgbox [:move-to-thread {:db/id (js/parseInt thread-id) :board/name board-name}]))
  (sec/defroute "/board/:board-name/:thread-id/:comment-no" [board-name thread-id comment-no]
    (put! msgbox [:move-to-thread {:db/id (js/parseInt thread-id) :board/name board-name}]))
  (sec/defroute "/articles/new" [query-params]
    (om/transact! app #(assoc %
                              :page :article
                              :target-thread (js/parseInt (:thread-id query-params))
                              :article {:article/name nil :article/blocks []})))
  (sec/defroute "/articles" []
    (fetch-articles app))
  (sec/defroute #"/article/(\d+)" [id]
    (fetch-article id app)))

(defn- setup-history []
  (let [history (goog.History.)
        navigation HistoryEventType/NAVIGATE]
    (events/listen history
                   navigation
                   #(-> % .-token sec/dispatch!))
    (.setEnabled history true)))

(defn init [app-state msgbox]
  (setup-routing app-state msgbox)
  (setup-history))
