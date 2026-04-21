(ns back-channeling.socket
    (:require [goog.events :as events])
    (:import [goog.net.WebSocket EventType]
             [goog.net WebSocket]))

(def ws (WebSocket. true))

(defn open [url & {:keys [on-message on-open on-close]}]
  (events/listen ws EventType.OPENED
                 (fn [_]
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
                 (fn [_]
                   #_(.log js/console (str "Websocket error" _))))
  (.open ws url))

(defn send [command message]
  (.send ws (pr-str [command message])))

