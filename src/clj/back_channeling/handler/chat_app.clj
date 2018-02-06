(ns back-channeling.handler.chat-app
  (:require [clojure.edn :as edn]
            [ring.util.response :refer [resource-response content-type header redirect]]
            [integrant.core :as ig]
            [liberator.dev]
            [hiccup.page :refer [include-js]]
            [hiccup.middleware :refer [wrap-base-url]]
            [hiccup.util :as util]
            [compojure.core :refer [GET POST routing routes] :as compojure]
            [compojure.route :as route]

            (back-channeling [layout :refer [layout]]
                             [signup :as signup]
                             [style :as style])
            [datomic.api :as d])
  (:import [java.io FileInputStream]))

(defn auth-by-password [{:keys [connection]} username password]
  (when (and (not-empty username) (not-empty password))
    (d/q '{:find [(pull ?s [:*]) .]
           :in [$ ?uname ?passwd]
           :where [[?s :user/name ?uname]
                   [?p :password-credential/user ?s]
                   [?p :password-credential/salt ?salt]
                   [(concat ?salt ?passwd) ?passwd-seq]
                   [(into-array Byte/TYPE ?passwd-seq) ?passwd-bytes]
                   [(buddy.core.hash/sha256 ?passwd-bytes) ?hash]
                   [(buddy.core.codecs/bytes->hex ?hash) ?hash-hex]
                   [?p :password-credential/password ?hash-hex]]}
         (d/db connection)
         username password)))

(defn index-view [req {:keys [prefix env plugin-js-path]}]
  (layout prefix req
   [:div#app.ui.page.full.height]
   (include-js (str prefix (if (= env :production)
                             "/js/back-channeling.min.js"
                             "/js/main.js")))
   (when plugin-js-path
     (include-js (if (.getScheme (util/to-uri plugin-js-path))
                   plugin-js-path
                   (util/url prefix plugin-js-path))))))

(defn login-view [req {:keys [prefix]}]
  (layout prefix
   req
   [:div.ui.middle.aligned.center.aligned.login.grid
    [:div.column
     [:h2.ui.header
      [:div.content
       [:img.ui.image {:src (str prefix "/img/logo.png")}]]]
     [:form.ui.large.login.form
      (merge {:method "post"}
             (when (= (:request-method req) :post)
               {:class "error"}))
      [:div.ui.stacked.segment
       [:div.ui.error.message
        [:p "User name or password is wrong."]]
       [:div.field
        [:div.ui.left.icon.input
         [:i.user.icon]
         [:input {:type "text" :name "username" :placeholder "User name"}]]]
       [:div.field
        [:div.ui.left.icon.input
         [:i.lock.icon]
         [:input {:type "password" :name "password" :placeholder "Password"}]]]
       [:button.ui.fluid.large.teal.submit.button {:type "submit"} "Login"]]]
     [:div.ui.message
      "New to us? " [:a {:href (str prefix "/signup")} "Sign up"]]]]))

(defn login-routes [{:keys [datomic] :as options}]
  (routes
   (GET "/login" req (login-view req options))
   (POST "/login" {{:keys [username password]} :params :as req}
     (if-let [user (auth-by-password datomic username password)]
       (-> (redirect (get-in req [:query-params "next"] "/"))
           (assoc-in [:session :identity] (select-keys user [:user/name :user/email])))
       (login-view req options)))
   (GET "/signup" req
     (signup/signup-view req options))
   (POST "/signup" req
     (signup/signup (select-keys (clojure.walk/keywordize-keys (:params req))
                                 [:user/email :user/name
                                  :password-credential/password
                                  :token-credential/token])
                    options))
   (POST "/logout" []
     (-> (redirect "/")
         (assoc :session {})))))

(defmethod ig/init-key :back-channeling.handler/chat-app
  [_ {:keys [datomic login-enabled? env prefix logout-route plugin-js-path]
      :or   {login-enabled? true}}]
  (let [r (routes
           (GET "/" req (index-view req {:prefix prefix :env env :plugin-js-path plugin-js-path}))
           (GET "/react/react.js" [] (-> (resource-response "cljsjs/development/react.inc.js")
                                         (content-type "text/javascript")))
           (GET "/react/react.min.js" [] (resource-response "cljsjs/production/react.min.inc.js"))
           (GET "/css/back-channeling.css" [] (-> {:body (style/build)}
                                                  (content-type "text/css")))
           (GET ["/voice/:thread-id/:filename" :thread-id #"\d+" :filename #"[0-9a-f\-]+\.ogg"] [thread-id filename]
             (let [content-type (cond (.endsWith filename ".wav") "audio/wav"
                                      (.endsWith filename ".ogg") "audio/ogg"
                                      :else (throw (IllegalArgumentException. filename)))]
               {:headers {"content-type" content-type}
                :body (FileInputStream. (str "voices/" thread-id "/" filename))})))]
    (if login-enabled?
      (routes r (login-routes {:prefix prefix :datomic datomic}))
      (if logout-route (routes r logout-route) r))))
