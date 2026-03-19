(ns back-channeling.auth.backend.session
  (:require [integrant.core :as ig]
            [ring.util.response :refer [redirect]]
            [buddy.auth.backends.session :refer [session-backend]]
            [back-channeling.auth.util :refer [api-access?]]))

(defn- handle-unauthorized-default
  "A default response constructor for an unauthorized request."
  [prefix request data]
  (if (api-access? request)
    {:status 401 :headers {} :body "Unauthorized"}
    (redirect (str prefix "/login?url=" (:uri request)))))

(defmethod ig/init-key :back-channeling.auth.backend/session
  [_ {:keys [prefix unauthorized-handler]
      :or {prefix ""}}]
  (let [handler (or unauthorized-handler (partial handle-unauthorized-default prefix))]
    (session-backend {:unauthorized-handler handler})))
