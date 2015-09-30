(ns back-channeling.token
  (:require [clojure.core.cache :as cache])
  (:import [java.util UUID]))

(defonce token-cache (atom (cache/ttl-cache-factory {} :ttl (* 30 60 1000))))

(defn new-token [user]
  (let [token (java.util.UUID/randomUUID)]
    (swap! token-cache assoc token user)
    token))

(defn auth-by [token]
  (cache/lookup @token-cache token))

