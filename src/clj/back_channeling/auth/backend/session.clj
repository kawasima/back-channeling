(ns back-channeling.auth.backend.session
  (:require [integrant.core :as ig]
            [buddy.auth.backends.session :refer [session-backend]]))

(defmethod ig/init-key :back-channeling.auth.backend/session [_ config]
  (session-backend))
