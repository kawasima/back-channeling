
(ns back-channeling.component.token
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]
            [clojure.core.cache :as cache])
  (:import [java.util UUID]))

(defn new-token [component user]
  (let [token (java.util.UUID/randomUUID)]
    (swap! (:token-cache component) assoc token user)
    token))

(defn auth-by [component token]
  (let [uuid-token (UUID/fromString token)]
    (cache/lookup @(:token-cache component) uuid-token)))


(defrecord TokenProvider []
  component/Lifecycle

  (start [component]
    (let [token-cache (atom (cache/ttl-cache-factory {} :ttl (* 30 60 1000)))]
      (assoc component :token-cache token-cache)))

  (stop [component]
    (dissoc component :token-cache)))

(defn token-provider-component [options]
  (map->TokenProvider options))
