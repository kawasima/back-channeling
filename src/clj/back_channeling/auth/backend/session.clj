(ns back-channeling.auth.backend.session
  (:require [integrant.core :as ig]
            [ring.util.response :refer [redirect]]
            [buddy.auth.backends.session :refer [session-backend]]))

(defn- api-access? [req]
  (if-let [accept (get-in req [:headers "accept"])]
    (or (.contains accept "application/json")
        (.contains accept "application/edn"))))

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
