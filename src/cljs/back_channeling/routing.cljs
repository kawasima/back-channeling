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

(defn fetch-articles [app]
  (api/request (str "/api/articles")
               {:handler (fn [response]
                           (om/transact! app
                                         #(assoc % :page {:type :article} :articles response)))}))

(defn fetch-article [id app]
  (api/request (str "/api/article/" id)
               {:handler (fn [response]
                           (om/transact! app
                                         #(assoc %
                                                 :page {:type :article}
                                                 :target-thread (js/parseInt (get-in response [:article/thread :db/id]))
                                                 :article response)))}))

(defn- setup-routing [app msgbox]
  (let [prefix (some-> js/document
                       (.querySelector "meta[property='bc:prefix']")
                       (.getAttribute "content"))]
    (sec/set-config! :prefix (str prefix "/#")))
  (sec/defroute "/" []
    (put! msgbox [:move-to-boards {}]))
  (sec/defroute "/board/:board-name" [board-name]
    (put! msgbox [:move-to-board {:board/name board-name}]))
  (sec/defroute "/board/:board-name/:thread-id" [board-name thread-id]
    (put! msgbox [:move-to-thread {:db/id (js/parseInt thread-id) :board/name board-name}]))
  (sec/defroute "/board/:board-name/:thread-id/:comment-no" [board-name thread-id comment-no]
    (put! msgbox [:move-to-thread {:db/id (js/parseInt thread-id) :board/name board-name}]))
  (sec/defroute "/articles/new" [query-params]
    (om/transact! app #(assoc %
                              :page {:type :article}
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
