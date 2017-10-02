(ns back-channeling.auth.backend.token
  (:require [integrant.core :as ig]
            [duct.logger :refer [log]]
            [buddy.auth.backends.token :refer [token-backend]]
            [back-channeling.boundary.tokens :as tokens]))

(defmethod ig/init-key :back-channeling.auth.backend/token [_ {:keys [cache logger]}]
  (token-backend
   {:authfn
    (fn [req token]
      (try
        (let [user (tokens/auth-by cache token)]
          (log logger :debug ::authenticated-token {:token token :user user})
          user)
        (catch Exception e
          (log logger :error ::authentiction-error e))))}))
