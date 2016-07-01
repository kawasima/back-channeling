(ns back-channeling.component.socketapp
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            (back-channeling.component [token :as token]))
  (:import [io.undertow.websockets.core WebSockets WebSocketCallback]))


(defprotocol ISendMessage
  (broadcast-message [this message])
  (multicast-message [this message users]))

(defn find-users [{:keys [channels path]}]
  (->> (get @channels path)
       vals
       (keep identity)
       (apply hash-set)))

(defn find-user-by-channel [{:keys [channels path]} ch]
  (get-in @channels [path ch]))

(defn find-user-by-name [{:keys [channels path]} user-name]
  (->> (get @channels path)
       vals
       (keep identity)
       (filter #(= (:user/name %) user-name))
       first))

(defn- token-from-request [exchange]
  (-> (.getRequestParameters exchange)
      (.get "token")
      first))

(defmulti handle-command (fn [socketapp msg ch] (first msg)))

(defmethod handle-command :leave [socketapp [_ message] ch]
  (log/info "disconnect" ch)
  (broadcast-message socketapp
                     [:leave {:user/name (:user/name message)
                              :user/email (:user/email message)}]))
(defmethod handle-command :call [socketapp [_ message] ch]
  (log/info "call from " (:from message) " to " (:to message))
  (multicast-message socketapp
                     [:call message]
                     (:to message)))

(defrecord SocketApp [token path]
  component/Lifecycle

  (start [component]
    (let [component (assoc component :channels (atom {}))]
      (assoc component
             :on-connect
             (fn [exchange channel]
               (log/debug "connect" channel)
               (if-let [user (token/auth-by token (token-from-request exchange))]
                 (do
                   (swap! (:channels component)
                          assoc-in [path channel] user)
                   (broadcast-message component [:join  {:user/name (:user/name user)
                                               :user/email (:user/email user)}]))))
             :on-message
             (fn [ch message]
               (log/debug "message=" message)
               (handle-command component
                               (edn/read-string message) ch))

             :on-close
             (fn [ch close-reason]
               (log/info "disconnect" ch "for" close-reason)
               (swap! (:channels component) update-in [path] dissoc ch)
               (handle-command component
                               [:leave (find-user-by-channel component ch)] ch)))))

  (stop [component]
    (dissoc component :path :on-message :on-close :channels))

  ISendMessage
  (broadcast-message [{:keys [channels path]} message]
    (doseq [[channel user] (get @channels path)]
      (WebSockets/sendText (pr-str message) channel
                           (proxy [WebSocketCallback] []
                             (complete [channel context])
                             (onError [channel context throwable])))))

  (multicast-message [{:keys [channels path]} message users]
    (doseq [[channel user] (get @channels path)]
      (when (users user)
        (WebSockets/sendText (pr-str message) channel
                             (proxy [WebSocketCallback] []
                               (complete [channel context])
                               (onError [channel context throwable]))))))

)

(defn socketapp-component [options]
  (map->SocketApp options))
