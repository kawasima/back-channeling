(ns back-channeling.auth.backend.bouncr
  (:require [integrant.core :as ig]
            [ring.util.response :refer [redirect]]

            [datomic.api :as d]
            [buddy.auth :refer [authenticated?]]
            [buddy.auth.protocols :as proto]
            [buddy.sign.jwt :as jwt]
            [compojure.core :refer [POST routes]]
            [camel-snake-kebab.core :refer :all]))

(alter-var-root #'buddy.sign.jws/+signers-map+
                (fn [m]
                  (assoc m :none {:signer   (fn [_ _ ] "")
                                  :verifier (fn [_ _ _] true)})))

(defn api-access? [req]
  (if-let [accept (get-in req [:headers "accept"])]
    (or (.contains accept "application/json")
        (.contains accept "application/edn"))))

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
    (register-user datomic
                   (:user/name data)
                   (:user/email data))))

(defmethod ig/init-key :back-channeling.auth.backend/bouncr
  [_ {:keys [datomic unauthorized-handler authfn pkey] :or {authfn authfn-default}}]
  (reify
    proto/IAuthentication
    (-parse [_ request]
      (merge
       (when-let [message (get-in request [:headers "x-bouncr-credential"])]
         (let [cred (jwt/unsign message "secret" {:alg :none})]
           {:user/name (:sub cred)
            :user/email (:email cred)
            :user/permissions (set (some->> (:permissions cred)
                                            (map #(keyword (->kebab-case %)))
                                            set))}))))
    (-authenticate [_ requst data]
      (authfn datomic data))

    proto/IAuthorization
    (-handle-unauthorized [_ request metadata]
      (if unauthorized-handler
        (unauthorized-handler request metadata)
        (handle-unauthorized-default request)))))

(defmethod ig/init-key :back-channeling.route.logout/bouncr
  [_ _]
  (routes
   (POST "/logout" []
     (redirect "/my/signOut" 308))))