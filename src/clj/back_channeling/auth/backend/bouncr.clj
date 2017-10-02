(ns back-channeling.auth.backend.bouncr
  (:require [integrant.core :as ig]
            [buddy.auth :refer [authenticated?]]
            [buddy.auth.protocols :as proto]))

(defn- handle-unauthorized-default
  "A default response constructor for an unauthorized request."
  [request]
  (if (authenticated? request)
    {:status 403 :headers {} :body "Permission denied"}
    {:status 401 :headers {} :body "Unauthorized"}))

(defmethod ig/init-key :back-channeling.auth.backend/bouncr
  [_ {:keys [unauthorized-handler authfn] :or {authfn identity}}]
  (reify
    proto/IAuthentication
    (-parse [_ request]
      (when-let [id (get-in request [:headers "x-bouncr-id"])]
        {:id id
         :permissions (some->> request
                               (get-in [:headers "x-bouncr-permissions"])
                               (clojure.string/split #"\s*,\s*")
                               vec)}))
    (-authenticate [_ requst data]
      (authfn data))

    proto/IAuthorization
    (-handle-unauthorized [_ request metadata]
      (if unauthorized-handler
        (unauthorized-handler request metadata)
        (handle-unauthorized-default request)))))
