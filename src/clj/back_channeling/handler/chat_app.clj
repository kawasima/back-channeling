(ns back-channeling.handler.chat-app
  (:require [clojure.edn :as edn]
            [ring.util.response :refer [resource-response content-type header redirect]]
            [integrant.core :as ig]
            [liberator.dev]
            [hiccup.page :refer [include-js]]
            [hiccup.util :as util]
            [compojure.core :refer [GET POST routing routes] :as compojure]
            [compojure.route :as route]

            (back-channeling [layout :refer [layout]]
                             [signup :as signup]
                             [style :as style])
            [buddy.hashers :as hashers]
            [buddy.core.hash]
            [buddy.core.codecs]
            [buddy.core.bytes]
            [datomic.api :as d])
  (:import [java.io FileInputStream]
           [java.nio.file Paths]))

(defn- legacy-sha256-check
  "Check password against legacy sha256(salt + password) hash.
   Uses constant-time comparison to prevent timing attacks."
  [password salt stored-hash]
  (let [passwd-bytes (into-array Byte/TYPE (concat salt (.getBytes password "UTF-8")))
        hash-bytes (buddy.core.hash/sha256 passwd-bytes)
        hash-hex (buddy.core.codecs/bytes->hex hash-bytes)]
    (buddy.core.bytes/equals? (.getBytes hash-hex "UTF-8") (.getBytes stored-hash "UTF-8"))))

(defn- upgrade-to-bcrypt
  "Replace legacy sha256 credential with bcrypt hash."
  [connection credential-id password salt]
  @(d/transact connection
     [[:db/retract credential-id :password-credential/salt salt]
      [:db/add credential-id :password-credential/password (hashers/derive password)]]))

(defn auth-by-password [{:keys [connection]} username password]
  (when (and (not-empty username) (not-empty password))
    (let [db (d/db connection)
          result (d/q '{:find [[(pull ?s [:*]) (pull ?p [:db/id :password-credential/password :password-credential/salt])]]
                        :in [$ ?uname]
                        :where [[?s :user/name ?uname]
                                [?p :password-credential/user ?s]]}
                      db username)]
      (when-let [[user credential] result]
        (let [stored-hash (:password-credential/password credential)
              salt (:password-credential/salt credential)]
          (cond
            ;; bcrypt hash (starts with "$2a$" or similar)
            (and stored-hash (.startsWith stored-hash "$"))
            (try
              (when (hashers/check password stored-hash)
                user)
              (catch Exception _ nil))

            ;; legacy sha256 hash — verify and upgrade
            (and stored-hash salt)
            (when (legacy-sha256-check password salt stored-hash)
              (upgrade-to-bcrypt connection (:db/id credential) password salt)
              user)))))))

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
       (let [next-url (get-in req [:query-params "next"] "/")
             safe-url (if (and (string? next-url)
                               (.startsWith next-url "/")
                               (not (.startsWith next-url "//"))
                               (not (re-find #"[\r\n\\]" next-url)))
                        next-url
                        "/")]
         (-> (redirect safe-url)
             (assoc-in [:session :identity] (select-keys user [:user/name :user/email]))))
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
  (let [voices-base-dir (try (.toRealPath (Paths/get "voices" (into-array String []))
                                          (into-array java.nio.file.LinkOption []))
                             (catch java.nio.file.NoSuchFileException _
                               (.toAbsolutePath (Paths/get "voices" (into-array String [])))))
        r (routes
           (GET "/" req (index-view req {:prefix prefix :env env :plugin-js-path plugin-js-path}))
           (GET "/react/react.js" [] (-> (resource-response "cljsjs/development/react.inc.js")
                                         (content-type "text/javascript")))
           (GET "/react/react.min.js" [] (resource-response "cljsjs/production/react.min.inc.js"))
           (GET "/css/back-channeling.css" [] (-> {:body (style/build)}
                                                  (content-type "text/css")))
           (GET ["/voice/:thread-id/:filename" :thread-id #"\d+" :filename #"[0-9a-f\-]+\.ogg"] [thread-id filename]
             (try
               (let [file-path (.toRealPath (Paths/get "voices" (into-array String [thread-id filename]))
                                            (into-array java.nio.file.LinkOption []))]
                 (if-not (.startsWith file-path voices-base-dir)
                   {:status 400 :headers {} :body "Invalid path"}
                   {:headers {"content-type" "audio/ogg"}
                    :body (FileInputStream. (.toString file-path))}))
               (catch java.nio.file.NoSuchFileException _
                 {:status 404 :headers {} :body "Not found"}))))]
    (if login-enabled?
      (routes r (login-routes {:prefix prefix :datomic datomic}))
      (if logout-route (routes r logout-route) r))))
