(ns back-channeling.system
  (:require [com.stuartsierra.component :as component]
            [duct.component.endpoint :refer [endpoint-component]]
            [duct.component.handler :refer [handler-component]]
            [duct.middleware.not-found :refer [wrap-not-found]]
            [duct.middleware.route-aliases :refer [wrap-route-aliases]]
            [meta-merge.core :refer [meta-merge]]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.util.response :refer [resource-response content-type header redirect]]
            
            [buddy.auth :refer [authenticated?]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.backends.token :refer [token-backend]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [buddy.auth.accessrules :refer [wrap-access-rules]]
            [buddy.auth.http :as http]

            (back-channeling.component [undertow  :refer [undertow-server]]
                                       [datomic   :refer [datomic-connection]]
                                       [migration :refer [migration-model]]
                                       [socketapp :refer [socketapp-component]]
                                       [token     :refer [token-provider-component] :as token])
            (back-channeling.endpoint [chat-app :refer [chat-app-endpoint]]
                                      [api      :refer [api-endpoint]])))

(defn api-access? [req]
  (if-let [accept (get-in req [:headers "accept"])]
    (or (.contains accept "application/json")
        (.contains accept "application/edn"))))

(def access-rules [{:pattern #"^(/|/api/(?!token).*)$"
                    :handler authenticated?}])

(defn token-base [token-provider]
  (token-backend
   {:authfn
    (fn [req token]
      (try
        (token/auth-by token-provider token)
        (catch Exception e)))}))

(def base-config
  {:app {:middleware [[wrap-not-found      :not-found]
                      [wrap-access-rules   :access-rules]
                      [wrap-authorization  :authorization]
                      [wrap-authentication :token-base :session-base]
                      [wrap-defaults       :defaults]]
         :access-rules {:rules access-rules :policy :allow}
         :not-found    "Resource Not Found"
         :defaults     (meta-merge site-defaults
                                   {:security {:anti-forgery false}})
         :session-base (session-backend)
         :authorization (fn [req meta]
                          (if (api-access? req)
                            (if (authenticated? req)
                              (http/response "Permission denied" 403)
                              (http/response "Unauthorized" 401))
                            (if (authenticated? req)
                              (redirect "/login")
                              (redirect (format "/login?next=%s" (:uri req))))))
         :aliases      {"/" "/index.html"}}
   :datomic {:uri "datomic:mem://bc"}})

(defn new-system [config]
  (let [config (meta-merge base-config config)
        token-provider (token-provider-component (:token config))
        config (-> config
                   (update-in [:app]
                              assoc :token-base (token-base token-provider)))]
    (-> (component/system-map
         :app       (handler-component   (:app config))
         :socketapp (socketapp-component (:socketapp config))
         :http      (undertow-server     (:http config))
         :token     token-provider
         :datomic   (datomic-connection (get-in config [:datomic :uri]))
         :migration (migration-model)
         :chat      (endpoint-component chat-app-endpoint)
         :api       (endpoint-component api-endpoint))
        (component/system-using
         {:http      [:app :socketapp]
          :app       [:chat :api]
          :socketapp [:token]
          :migration [:datomic]
          :chat      [:datomic]
          :api       [:datomic :token :socketapp]}))))
