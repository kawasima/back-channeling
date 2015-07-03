(ns back-channeling.core
  (:use [hiccup.core :only [html]]
        [hiccup.page :only [include-js]]
        [environ.core :only [env]]
        [ring.util.response :only [resource-response content-type header redirect]]
        [ring.middleware.defaults :only [wrap-defaults site-defaults]]
        [ring.middleware.reload :only [wrap-reload]]

        [buddy.auth :only [authenticated?]]
        [buddy.auth.backends.session :only [session-backend]]
        [buddy.auth.middleware :only [wrap-authentication wrap-authorization]]
        [buddy.auth.accessrules :only [wrap-access-rules]]
        (back-channeling [resources :only [api-routes]]
                         [layout :only [layout]]))
  (:require [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [hiccup.middleware :refer [wrap-base-url]]
            [compojure.core :refer [defroutes GET POST routing] :as compojure]
            [compojure.route :as route]
            (back-channeling [server :as server]
                             [style :as style]
                             [model :as model]
                             [signup :as signup]))
  (:import [java.util Date]))



(defn index-view [req]
  (layout req
   [:div#app.ui.page]
   (include-js (str "/js/extern/back-channeling"
                    (when-not (:dev env) ".min") ".js"))))
(defn login-view [req]
  (layout req
   [:div.ui.middle.aligned.center.aligned.login.grid
    [:div.column
     [:h2.ui.header
      [:div.content "Back channeling"]]
     [:form.ui.large.login.form {:method "post"}
      [:div.ui.stacked.segment
       [:div.field
        [:div.ui.left.icon.input
         [:i.user.icon]
         [:input {:type "text" :name "username" :placeholder "User name"}]]]
       [:div.field
        [:div.ui.left.icon.input
         [:i.lock.icon]
         [:input {:type "password" :name "password" :placeholder "Password"}]]]
       [:button.ui.fluid.large.teal.submit.button {:type "submit"} "Login"]]
      [:div.ui.error.message]]
     [:div.ui.message
      "New to us?" [:a {:href "/signup"} "Sign up"]]]]))

(defn auth-by-password [username password]
  (when (and (not-empty username) (not-empty password))
    (model/query '{:find [(pull ?s [:*]) .]
                 :in [$ ?uname ?passwd]
                 :where [[?s :user/name ?uname]
                         [?s :user/salt ?salt]
                         [(concat ?salt ?passwd) ?passwd-seq]
                         [(into-array Byte/TYPE ?passwd-seq) ?passwd-bytes]
                         [(buddy.core.hash/sha256 ?passwd-bytes) ?hash]
                         [(buddy.core.codecs/bytes->hex ?hash) ?hash-hex]
                         [?s :user/password ?hash-hex]]} username password)))

(defroutes app-routes
  (GET "/" req (index-view req))
  (GET "/login" req (login-view req))
  (POST "/login" {{:keys [username password]} :params :as req}
    (if-let [user (auth-by-password username password)]
        (-> (redirect (get-in req [:query-params "next"] "/"))
            (assoc-in [:session :identity] (select-keys user [:user/name :user/email])))
        (login-view req)))
  (GET "/signup" req
    (signup/signup-view req))
  (POST "/signup" req
    (signup/signup (select-keys (clojure.walk/keywordize-keys (:params req))
                                [:user/email :user/name :user/password])))
  
  (GET "/logout" []
    (-> (redirect "/")
        (assoc :session {})))
  
  (GET "/react/react.js" [] (-> (resource-response "cljsjs/development/react.inc.js")
                                (content-type "text/javascript")))
  (GET "/react/react.min.js" [] (resource-response "cljsjs/production/react.min.inc.js"))
  (compojure/context "/api" [] api-routes)
  (GET "/css/back-channeling.css" [] (-> {:body (style/build)}
                                         (content-type "text/css")))
  (route/resources "/")
  (route/not-found "Not found."))

(defmulti handle-command (fn [msg ch] (first msg)))

(defmethod handle-command :leave [[_ message] ch]
  (log/info "disconnect" ch)
  (server/broadcast-message "/ws" [:leave {:user/name (:user/name message)}]))

(defmethod handle-command :join [[_ message] ch]
  (server/broadcast-message "/ws" [:join  {:user/name (:user/name message)}]))

(def access-rules [{:pattern #"^(/|/api/?)$" :handler authenticated?}])
(def backend (session-backend
              {:unauthorized-handler
               (fn [req meta]
                 (if (authenticated? req)
                   (redirect "/login")
                   (redirect (format "/login?next=%s" (:uri req)))))}))

(defn -main [& {:keys [port] :or {port 3009}}]
  (model/create-schema)
  (server/run-server
   (-> app-routes
       (wrap-access-rules {:rules access-rules :policy :allow})
       (wrap-authentication backend)
       (wrap-authorization backend)
       (wrap-defaults (assoc-in site-defaults [:security :anti-forgery] false))
       (wrap-reload))
   :port port
   :websockets [{:path "/ws"
                 :on-message (fn [ch message]
                               (handle-command (edn/read-string message) ch))
                 :on-close (fn [ch close-reason]
                             (log/info "disconnect" ch "for" close-reason)
                             (handle-command [:leave nil] ch))}]))
