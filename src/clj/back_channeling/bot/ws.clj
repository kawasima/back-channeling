(ns back-channeling.bot.ws
  "WebSocket connection management for the bot."
  (:require [clojure.edn :as edn])
  (:import [java.net URI]
           [java.net.http HttpClient WebSocket$Listener]))

(defn connect!
  "Open a WebSocket connection. Returns the WebSocket instance.
   `on-message` receives the parsed raw string for each complete message.
   `on-close` is called with status-code and reason.
   `on-error` is called with the throwable."
  [ws-url {:keys [on-message on-close on-error]}]
  (let [buf (StringBuilder.)
        client (HttpClient/newHttpClient)
        listener (reify WebSocket$Listener
                   (onText [_ webSocket data last?]
                     (.append buf data)
                     (when last?
                       (let [message (.toString buf)]
                         (.setLength buf 0)
                         (on-message message)))
                     (.request webSocket 1)
                     nil)
                   (onClose [_ _webSocket status-code reason]
                     (on-close status-code reason))
                   (onError [_ _webSocket error]
                     (on-error error)))]
    (-> (.newWebSocketBuilder client)
        (.buildAsync (URI. ws-url) listener)
        (.join))))
