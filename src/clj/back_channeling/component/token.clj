(ns back-channeling.component.token
  "Provides a token for authorization."
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]
            [clojure.core.cache :as cache])
  (:import [java.util UUID]))

(defprotocol ITokenProvider
  (new-token [this user])
  (auth-by   [this token]))

(defrecord TokenProvider [disposable?]
  component/Lifecycle

  (start [component]
    (if (:token-cache component)
      component
      (let [token-cache (atom (cache/ttl-cache-factory {} :ttl (* 30 60 1000)))]
        (assoc component :token-cache token-cache))))

  (stop [component]
    (if disposable?
      (dissoc component :token-cache)
      component))

  ITokenProvider
  (new-token [component user]
    (let [token (java.util.UUID/randomUUID)]
      (swap! (:token-cache component) assoc token user)
      token))

  (auth-by [component token]
    (let [uuid-token (condp instance? token
                       String (UUID/fromString token)
                       UUID   token)]
      (cache/lookup @(:token-cache component) uuid-token))))

(defn token-provider-component [options]
  (map->TokenProvider options))
