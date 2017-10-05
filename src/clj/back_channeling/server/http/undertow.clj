(ns back-channeling.server.http.undertow
  (:require [integrant.core :as ig]
            [duct.logger :refer [log]]
            [ring.util.servlet :as servlet]
            [back-channeling.websocket.socketapp :as socketapp]
            [compojure.core :refer [context]])
  (:import [java.util UUID]
           [java.nio.file Paths]
           [org.xnio ByteBufferSlicePool]
           [io.undertow Undertow Handlers]
           [io.undertow.servlet Servlets]
           [io.undertow.servlet.api DeploymentInfo]
           [io.undertow.servlet.util ImmediateInstanceFactory]
           [io.undertow.websockets WebSocketConnectionCallback ]
           [io.undertow.websockets.core WebSockets WebSocketCallback AbstractReceiveListener]
           [io.undertow.websockets.jsr WebSocketDeploymentInfo]))

(defn websocket-callback [socketapp]
  (proxy [WebSocketConnectionCallback] []
    (onConnect [exchange channel]
      (.. channel
          getReceiveSetter
          (set (proxy [AbstractReceiveListener] []
                 (onFullTextMessage
                   [channel message]
                   (socketapp/on-message socketapp channel (.getData message)))
                 #_(onCloseMessage
                   [message channel]
                   (when on-close (on-close channel message))))))
      (.resumeReceives channel)
      (.addCloseTask channel
                     (proxy [org.xnio.ChannelListener] []
                       (handleEvent [channel]
                         (socketapp/on-close socketapp channel nil))))
      (socketapp/on-connect socketapp exchange channel))))


(defn- run-server [ring-handler & {:keys [port websockets prefix]}]
  (let [ring-servlet (servlet/servlet ring-handler)
        servlet-builder (.. (Servlets/deployment)
                            (setClassLoader (.getContextClassLoader (Thread/currentThread)))
                            (setContextPath (or prefix ""))
                            (setDeploymentName "back-channeling")
                            (addServlets
                             (into-array
                              [(.. (Servlets/servlet "Ring handler"
                                                     (class ring-servlet)
                                                     (ImmediateInstanceFactory. ring-servlet))
                                   (addMapping "/*"))])))
        container (Servlets/defaultContainer)
        servlet-manager (.addDeployment container servlet-builder)
        handler (Handlers/path)]
    ;; deploy
    (.deploy servlet-manager)

    (doseq [ws websockets]
      (.addPrefixPath handler
                      (-> (Paths/get (or prefix "") (into-array String [(:path ws)]))
                          str
                          (clojure.string/replace #"\\" "/"))
                      (Handlers/websocket
                       (websocket-callback ws))))
    (let [server (.. (Undertow/builder)
                     (addHttpListener port "0.0.0.0")
                     (setHandler (.addPrefixPath handler "/" (.start servlet-manager)))
                     (build))]
      (.start server)
      server)))

(defmethod ig/init-key :back-channeling.server.http/undertow [_ {:keys [logger port prefix] :as opts}]
  (let [handler (atom (delay (:handler opts)))
        logger  (atom logger)]
    (log @logger :report ::starting-server (select-keys opts [:port :prefix]))
    {:handler handler
     :logger  logger
     :server  (run-server (context prefix [] @@handler)
                          :prefix prefix
                          :port port
                          :websockets [(:socketapp opts)])}))

(defmethod ig/halt-key! :back-channeling.server.http/undertow [_ {:keys [server logger]}]
  (log @logger :report ::stopping-server)
  (.stop server))

(defmethod ig/suspend-key! :duct.server.http/undertow [_ {:keys [handler]}]
  (reset! handler (promise)))

(defmethod ig/resume-key :duct.server.http/undertow [key opts old-opts old-impl]
  (if (= (dissoc opts :handler :logger) (dissoc old-opts :handler :logger))
    (do (deliver @(:handler old-impl) (:handler opts))
        (reset! (:logger old-impl) (:logger opts))
        old-impl)
    (do (ig/halt-key! key old-impl)
        (ig/init-key key opts))))
