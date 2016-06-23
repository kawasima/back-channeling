(ns back-channeling.endpoint.chat-app
  (:require [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [ring.util.response :refer [resource-response content-type header redirect]]
            [liberator.dev]
            [environ.core :refer [env]]
            [hiccup.page :refer [include-js]]
            [hiccup.middleware :refer [wrap-base-url]]
            [compojure.core :refer [GET POST routes] :as compojure]
            [compojure.route :as route]
            
            (back-channeling [layout :refer [layout]]
                             [signup :as signup]
                             [style :as style])
            (back-channeling.component [token :as token]
                                       [datomic :as d]))
  (:import [java.io FileInputStream]))

(defn auth-by-password [datomic username password]
  (when (and (not-empty username) (not-empty password))
    (d/query datomic
             '{:find [(pull ?s [:*]) .]
               :in [$ ?uname ?passwd]
               :where [[?s :user/name ?uname]
                       [?s :user/salt ?salt]
                       [(concat ?salt ?passwd) ?passwd-seq]
                       [(into-array Byte/TYPE ?passwd-seq) ?passwd-bytes]
                       [(buddy.core.hash/sha256 ?passwd-bytes) ?hash]
                       [(buddy.core.codecs/bytes->hex ?hash) ?hash-hex]
                       [?s :user/password ?hash-hex]]}
             username password)))

(defn index-view [req]
  (layout req
   [:div#app.ui.page.full.height]
   (include-js (str "/js/back-channeling"
                    (when-not (:dev env) ".min") ".js"))))

(defn login-view [req]
  (layout
   req
   [:div.ui.middle.aligned.center.aligned.login.grid
    [:div.column
     [:h2.ui.header
      [:div.content
       [:img.ui.image {:src "/img/logo.png"}]]]
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
      "New to us? " [:a {:href "/signup"} "Sign up"]]]]))

(defn chat-app-endpoint [{:keys [datomic]}]
  (routes
   (GET "/" req (index-view req))
   (GET "/login" req (login-view req))
   (POST "/login" {{:keys [username password]} :params :as req}
     (if-let [user (auth-by-password datomic username password)]
       (-> (redirect (get-in req [:query-params "next"] "/"))
           (assoc-in [:session :identity] (select-keys user [:user/name :user/email])))
       (login-view req)))
   (GET "/signup" req
     (signup/signup-view req))
   (POST "/signup" req
     (signup/signup datomic
                    (select-keys (clojure.walk/keywordize-keys (:params req))
                                 [:user/email :user/name :user/password :user/token])))
   
   (GET "/logout" []
     (-> (redirect "/")
         (assoc :session {})))
   
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
        :body (FileInputStream. (str "voices/" thread-id "/" filename))}))))
