(ns back-channeling.boundary.tokens
  (:require [integrant.core :as ig]
            [clojure.core.cache :as cache]
            [back-channeling.database.cache])
  (:import [java.util UUID]))

(defprotocol Tokens
  (new-token [cache user])
  (auth-by   [cache token]))

(extend-protocol Tokens
  back_channeling.database.cache.Boundary
  (new-token [{:keys [cache]} user]
    (let [token (UUID/randomUUID)]
      (swap! cache assoc token user)
      token))

  (auth-by [{:keys [cache]} token]
    (let [uuid-token (condp instance? token
                       String (UUID/fromString token)
                       UUID   token)]
      (cache/lookup @cache uuid-token))))
