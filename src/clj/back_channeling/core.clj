(ns back-channeling.core
  (:use [hiccup.core :only [html]]
        [hiccup.page :only [html5 include-css include-js]]
        [ring.util.response :only [resource-response content-type header]]
        [environ.core :only [env]])
  (:require [hiccup.middleware :refer [wrap-base-url]]
            [compojure.core :refer [defroutes GET POST routing]]
            [compojure.handler :as handler]
            [compojure.route :as route]
            (back-channeling [style :as style]
                             [model :as model])))

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
  (GET "/react/react.js" [] (-> (resource-response "cljsjs/development/react.inc.js")
                                (content-type "text/javascript")))
  (GET "/react/react.min.js" [] (resource-response "cljsjs/production/react.min.inc.js"))
  (GET "/css/back-channeling.css" [] (-> {:body (style/build)}
                                         (content-type "text/css")))
  (route/resources "/"))

(defmulti handle-command (fn [msg ch] (:command msg)))

(defmethod handle-command :post [message ch]
  (model/transact [{:db/id #db/id[db.part/user -1]
                    :message/post-at (Date.)
                    :message/posted-by nil}
                   {:db/id #db/id[db.part/user -2]
                    :thrad/title (get-in message [:thead :title])}
                   [:db/add #db/id[db.part/user -2]
                    :thead/messages #db/id[db.part/user -1]]]))

(defn multicast []
  )

(def app
  (routing
   (-> (handler/site app-routes)
       (wrap-base-url))))

(defn -main [& {:keys [port] :or {port 3009}}]
  (server/run-server
   (-> app-routes
       (wrap-defaults api-defaults)
       wrap-reload)
   :port port
   :websockets [{:path "/ws"
                 :on-message (fn [ch message]
                               (handle-command (edn/read-string message) ch))
                 :on-close (fn [ch close-reason]
                             (log/info "disconnect" ch "for" close-reason)
                             (handle-command {:command :bye} ch))}]))
