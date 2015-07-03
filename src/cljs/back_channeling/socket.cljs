(ns back-channeling.socket
    (:require-macros [cljs.core.async.macros :refer [go go-loop]])
    (:require [cljs.core.async :refer [put! <! chan pub sub unsub-all]]
              [clojure.browser.net :as net]
              [goog.events :as events]
              [goog.string :as gstring]
              [goog.ui.Component]
              [goog.net.ErrorCode]
              [goog.net.EventType])
    (:use [cljs.reader :only [read-string]])
    (:import [goog.events KeyCodes]
             [goog.net.WebSocket EventType]
             [goog.net WebSocket]))

(def ws (WebSocket. true (fn [] 10)))

(defn open [url & {:keys [on-message on-open]}]
  (events/listen ws EventType.OPENED
                 (fn [e]
                   (when on-open
                     (on-open))))
  (events/listen ws EventType.MESSAGE
                 (fn [e]
                   (when on-message
                     (on-message (.-message e)))))
  (events/listen ws EventType.CLOSED
                 (fn [e]
                   (.log js/console "Websocket closed.")))
  (events/listen ws EventType.ERROR
                 (fn [e]
                   (.log js/console (str "Websocket error" e))))
  (.open ws url))

(defn send [message]
  (.send ws (pr-str message)))

