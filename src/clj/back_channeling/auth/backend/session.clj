(ns back-channeling.auth.backend.session
  (:require [integrant.core :as ig]
            [ring.util.response :refer [redirect]]
            [buddy.auth.backends.session :refer [session-backend]]
            [back-channeling.auth.util :refer [api-access?]]))

(defn- handle-unauthorized-default
  "A default response constructor for an unauthorized request."
  [request data]
  (if (api-access? request)
    {:status 401 :headers {} :body "Unauthorized"}
    ;; FIXME shoud prepend the given prefix.
    (redirect (str "/login?url=" (:uri request)))))

(defmethod ig/init-key :back-channeling.auth.backend/session
  [_ {:keys [unauthorized-handler]
      :or {unauthorized-handler handle-unauthorized-default}}]
  (session-backend {:unauthorized-handler unauthorized-handler}))
