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

(def ws (WebSocket. true))

(defn open [url & {:keys [on-message on-open on-close]}]
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
                   (when on-close
                     (on-close e))
                   #_(.log js/console "Websocket closed.")))
  (events/listen ws EventType.ERROR
                 (fn [e]
                   #_(.log js/console (str "Websocket error" e))))
  (.open ws url))

(defn send [command message]
  (.send ws (pr-str [command message])))

