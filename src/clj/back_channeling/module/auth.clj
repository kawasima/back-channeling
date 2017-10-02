(ns back-channeling.module.auth
  (:require [integrant.core :as ig]
            [duct.core :as core]
            [ring.util.response :refer [header redirect resource-response content-type]]
            [buddy.auth :refer [authenticated?]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.backends.token :refer [token-backend]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [buddy.auth.accessrules :refer [wrap-access-rules]]
            [buddy.auth.http :as http]))

(defn wrap-same-origin-policy [handler console]
  (fn [req]
    (if (:uri console)
      (if (= (:request-method req) :options)
        ;;Pre-flight request
        {:status 200
        :headers {"Access-Control-Allow-Methods" "POST,GET,PUT,DELETE,OPTIONS"
                  "Access-Control-Allow-Origin" (:uri console)
                  "Access-Control-Allow-Headers" "Origin, Authorization, Accept, Content-Type"
                  "Access-Control-Allow-Credentials" "true"}}
        (when-let [resp (handler req)]
          (-> resp
              (header "Access-Control-Allow-Origin" (:uri console))
              (header "Access-Control-Allow-Credentials" "true"))))
      (handler req))))

(defn api-access? [req]
  (if-let [accept (get-in req [:headers "accept"])]
    (or (.contains accept "application/json")
        (.contains accept "application/edn"))))


(defmethod ig/init-key :back-channeling.auth/access-rules [_ options]
  [{:pattern #"^(/|/api/(?!token).*)$"
    :handler authenticated?}])

(defmethod ig/init-key :back-channeling.middleware/access-rules
  [_ {:keys [rules policy] :or {policy :allow}}]
  #(wrap-access-rules % {:rules rules :policy policy}))

(defmethod ig/init-key :back-channeling.middleware/authorization [_ options]
  #(wrap-authorization
    %
    (fn [req meta]
      (if (api-access? req)
        (if (authenticated? req)
          (http/response "Permission denied" 403)
          (http/response "Unauthorized" 401))
        (if (authenticated? req)
          (redirect "/login")
          (redirect (str "/login?next=" (:uri req))))))))

(defmethod ig/init-key :back-channeling.middleware/authentication [_ {:keys [backends]}]
  #(apply wrap-authentication % backends))

(defmethod ig/init-key :back-channeling.middleware/same-origin-policy [_ {:keys [console]}]
  #(wrap-same-origin-policy % console))

(defmethod ig/init-key :back-channeling.module/auth [_ options]
  {:req #{:duct/logger}
   :fn  (fn [config]
          (core/merge-configs config
                              options
                              {:duct.core/handler
                               {:middleware ^distinct
                                [(ig/ref :back-channeling.middleware/access-rules)
                                 (ig/ref :back-channeling.middleware/authorization)
                                 (ig/ref :back-channeling.middleware/authentication)
                                 (ig/ref :back-channeling.middleware/same-origin-policy)]}
                               :back-channeling.auth/access-rules {}
                               :back-channeling.middleware/authentication {}
                               :back-channeling.middleware/authorization {}
                               :back-channeling.middleware/access-rules
                               {:rules (ig/ref :back-channeling.auth/access-rules)}
                               :back-channeling.middleware/same-origin-policy {}}))})
