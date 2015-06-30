(ns back-channeling.core
  (:use [hiccup.core :only [html]]
        [hiccup.page :only [html5 include-css include-js]]
        [ring.util.response :only [resource-response content-type header]]
        [environ.core :only [env]]
        [ring.middleware.defaults :only [wrap-defaults site-defaults]]
        [ring.middleware.reload :only [wrap-reload]]
        [back-channeling.resources :only [api-routes]])
  (:require [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [hiccup.middleware :refer [wrap-base-url]]
            [compojure.core :refer [defroutes GET POST routing] :as compojure]
            [compojure.route :as route]
            (back-channeling [server :as server]
                             [style :as style]
                             [model :as model]
                             ))
  (:import [java.util Date]))

(def watchers (atom {}))

(defn layout [& body]
  (html5
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1, maximum-scale=1"}]
    (include-css "/css/vendors/semantic.min.css")
    #_(include-js )
    (when (:dev env) (include-js "/react/react.js"))]
   [:body body]))


(defn index []
  (layout
   [:div#app.ui.page]
   (include-js (str "/js/extern/back-channeling"
                    (when-not (:dev env) ".min") ".js"))))

(defroutes app-routes
  (GET "/" [] (index))
  (GET "/message" []
    (server/broadcast-message "/ws" "Hello"))
  (GET "/react/react.js" [] (-> (resource-response "cljsjs/development/react.inc.js")
                                (content-type "text/javascript")))
  (GET "/react/react.min.js" [] (resource-response "cljsjs/production/react.min.inc.js"))
  (compojure/context "/api" [] api-routes)
  (GET "/css/back-channeling.css" [] (-> {:body (style/build)}
                                         (content-type "text/css")))
  (route/resources "/")
  (route/not-found "Not found."))

(defmulti handle-command (fn [msg ch] (:command msg)))

(defmethod handle-command :post [message ch]
  )

(defmethod handle-command :bye [message ch]
  (log/info "disconnect" ch))

(defn -main [& {:keys [port] :or {port 3009}}]
  (model/create-schema)
  (server/run-server
   (-> app-routes
       (wrap-defaults (assoc-in site-defaults [:security :anti-forgery] false))
       (wrap-reload))
   :port port
   :websockets [{:path "/ws"
                 :on-message (fn [ch message]
                               (handle-command (edn/read-string message) ch))
                 :on-close (fn [ch close-reason]
                             (log/info "disconnect" ch "for" close-reason)
                             (handle-command {:command :bye} ch))}]))
