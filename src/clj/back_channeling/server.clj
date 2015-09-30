(ns back-channeling.server
  (:require [ring.util.servlet :as servlet]
            [clojure.tools.logging :as log]
            [back-channeling.token :as token])
  (:import [java.util UUID]
           [org.xnio ByteBufferSlicePool]
           [io.undertow Undertow Handlers]
           [io.undertow.servlet Servlets]
           [io.undertow.servlet.api DeploymentInfo]
           [io.undertow.servlet.util ImmediateInstanceFactory]
           [io.undertow.websockets WebSocketConnectionCallback ]
           [io.undertow.websockets.core WebSockets WebSocketCallback AbstractReceiveListener]
           [io.undertow.websockets.jsr WebSocketDeploymentInfo]))

(defonce channels (atom {}))

(defn broadcast-message [path message]
  (doseq [[channel user] (get @channels path)]
    (WebSockets/sendText (pr-str message) channel
                         (proxy [WebSocketCallback] []
                           (complete [channel context])
                           (onError [channel context throwable])))))

(defn multicast-message [path message users]
  (doseq [[channel user] (get @channels path)]
    (when (users user)
      (WebSockets/sendText (pr-str message) channel
                           (proxy [WebSocketCallback] []
                             (complete [channel context])
                             (onError [channel context throwable]))))))

(defn find-users [path]
  (->> (get @channels path)
       vals
       (keep identity)
       (apply hash-set)))

(defn find-user-by-channel [path ch]
  (println @channels)
  (println path ch (get-in @channels [path ch]))
  (get-in @channels [path ch]))

(defn find-user-by-name [path user-name]
  (->> (get @channels path)
       vals
       (keep identity)
       (filter #(= (:user/name %) user-name))
       first))

(defn- token-from-request [exchange]
  (if-let [token-str (-> (.getRequestParameters exchange)
                         (.get "token")
                         first)]
    (try
      (UUID/fromString token-str)
      (catch Exception e))))

(defn websocket-callback [path {:keys [on-close on-message]}]
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
                         (when on-close (on-close channel nil))
                         (swap! channels update-in [path] dissoc channel))))
      (if-let [user (token/auth-by (token-from-request exchange))]
        (do
          (swap! channels assoc-in [path channel] user)
          (broadcast-message "/ws" [:join  {:user/name (:user/name user)
                                            :user/email (:user/email user)}]))))))

(defn run-server [ring-handler & {port :port websockets :websockets}]
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
      (swap! channels assoc (:path ws) {})
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

