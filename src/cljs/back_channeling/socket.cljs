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

(def ws (WebSocket. true 10))

(defn open [url]
  (events/listen ws EventType.MESSAGE
                 (fn [e]))
  (.open ws url))

