(ns back-channeling.auth.backend.bouncr
  (:require [integrant.core :as ig]
            [ring.util.response :refer [redirect]]

            [datomic.api :as d]
            [buddy.auth.protocols :as proto]
            [buddy.sign.jwt :as jwt]
            [compojure.core :refer [POST routes]]
            [camel-snake-kebab.core :refer :all]
            [back-channeling.auth.util :refer [api-access?]]))

(defn- handle-unauthorized-default
  "A default response constructor for an unauthorized request."
  [request]
  (if (api-access? request)
    {:status 401 :headers {} :body "Unauthorized"}
    (redirect (str "/my/signIn?url=" (:uri request)))))

(defn register-user [{:keys [connection]} user-name email]
  (-> (d/transact connection
                  [{:db/id (d/tempid :db.part/user)
                    :user/name  user-name
                    :user/email email}])
      deref
      :tx-data))

(defn authfn-default [{:keys [connection] :as datomic} data]
  (if-let [user (d/q '{:find [?u .]
                    :in [$ ?n]
                    :where [[?u :user/name ?n]]}
                  (d/db connection) (data :user/name))]
    (merge {:db/id user} data)
    (do (register-user datomic
                       (:user/name data)
                       (:user/email data))
        ;; Re-query to get the resolved entity id for the newly created user
        (let [user-id (d/q '{:find [?u .]
                             :in [$ ?n]
                             :where [[?u :user/name ?n]]}
                           (d/db connection) (:user/name data))]
          (merge {:db/id user-id} data)))))

(defmethod ig/init-key :back-channeling.auth.backend/bouncr
  ;; Returns nil when :pkey is not configured. Do not include this
  ;; component in :backends when Bouncr integration is not in use.
  [_ {:keys [datomic unauthorized-handler authfn pkey] :or {authfn authfn-default}}]
  (when pkey
    (reify
    proto/IAuthentication
    (-parse [_ request]
      (merge
       (when-let [message (get-in request [:headers "x-bouncr-credential"])]
         (try
           (let [cred (jwt/unsign message pkey {:alg :hs256})]
             {:user/name (:sub cred)
              :user/email (:email cred)
              :user/permissions (set (some->> (:permissions cred)
                                              (map #(keyword (->kebab-case %)))
                                              set))})
           (catch Exception _ nil)))))
    (-authenticate [_ request data]
      (authfn datomic data))

    proto/IAuthorization
    (-handle-unauthorized [_ request metadata]
      (if unauthorized-handler
        (unauthorized-handler request metadata)
        (handle-unauthorized-default request))))))

(defmethod ig/init-key :back-channeling.route.logout/bouncr
  [_ _]
  (routes
   (POST "/logout" []
     (redirect "/my/signOut" 308))))
