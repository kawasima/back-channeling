(ns back-channeling.component.undertow
  (:require [com.stuartsierra.component :as component]
            [ring.util.servlet :as servlet]
            [clojure.tools.logging :as log]
            [compojure.core :refer [context]])
  (:import [java.util UUID]
           [org.xnio ByteBufferSlicePool]
           [io.undertow Undertow Handlers]
           [io.undertow.servlet Servlets]
           [io.undertow.servlet.api DeploymentInfo]
           [io.undertow.servlet.util ImmediateInstanceFactory]
           [io.undertow.websockets WebSocketConnectionCallback ]
           [io.undertow.websockets.core WebSockets WebSocketCallback AbstractReceiveListener]
           [io.undertow.websockets.jsr WebSocketDeploymentInfo]))

(defn websocket-callback [path {:keys [on-close on-message on-connect]}]
  (proxy [WebSocketConnectionCallback] []
    (onConnect [exchange channel]
      (.. channel
          getReceiveSetter
          (set (proxy [AbstractReceiveListener] []
                 (onFullTextMessage
                   [channel message]
                   (when on-message (on-message channel (.getData message))))
                 #_(onCloseMessage
                   [message channel]
                   (when on-close (on-close channel message))))))
      (.resumeReceives channel)
      (.addCloseTask channel
                     (proxy [org.xnio.ChannelListener] []
                       (handleEvent [channel]
                         (when on-close (on-close channel nil)))))
      (when on-connect
        (on-connect exchange channel)))))


(defn- run-server [ring-handler & {port :port websockets :websockets}]
  (let [ring-servlet (servlet/servlet ring-handler)
        servlet-builder (.. (Servlets/deployment)
                            (setClassLoader (.getContextClassLoader (Thread/currentThread)))
                            (setContextPath "")
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
                      (:path ws)
                      (Handlers/websocket
                       (websocket-callback (:path ws) (dissoc ws :path)))))
    (let [server (.. (Undertow/builder)
                     (addHttpListener port "0.0.0.0")
                     (setHandler (.addPrefixPath handler "/" (.start servlet-manager)))
                     (build))]
      (.start server)
      server)))

(defrecord UndertowServer [app socketapp port prefix]
  component/Lifecycle
  (start [component]
    (if (:server component)
      component
      (let [server (run-server (context prefix [] (:handler app))
                              :prefix prefix
                              :port port
                              :websockets [socketapp])]
        (assoc component
               :server server))))
  (stop [component]
    (when-let [server (:server component)]
      (.stop server))
    (dissoc component :server)))

(defn undertow-server
  "Create an Undertow server component."
  [options]
  (map->UndertowServer options))
