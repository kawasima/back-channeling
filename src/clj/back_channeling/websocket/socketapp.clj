(ns back-channeling.websocket.socketapp
  (:require [integrant.core :as ig]
            [duct.logger :refer [log]]
            [clojure.edn :as edn]
            (back-channeling.boundary [tokens :as tokens]))
  (:import [io.undertow.websockets.core WebSockets WebSocketCallback]))


(defprotocol ISendMessage
  (broadcast-message [this message])
  (multicast-message [this message users])
  (on-connect [this exchange channel])
  (on-message [this channel message])
  (on-close   [this channel close-reason]))

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
  (broadcast-message socketapp
                     [:leave {:user/name (:user/name message)
                              :user/email (:user/email message)}]))
(defmethod handle-command :call [socketapp [_ message] ch]
  (multicast-message socketapp
                     [:call message]
                     (:to message)))

(defrecord Socketapp [channels path]
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

  (on-connect [{:keys [channels path cache] :as socketapp} exchange channel]
    (if-let [user (tokens/auth-by cache (token-from-request exchange))]
      (do
        (swap! channels assoc-in [path channel] user)
        (broadcast-message socketapp [:join {:user/name (:user/name user)
                                             :user/email (:user/email user)}]))))
  (on-message [socketapp ch message]
    (handle-command socketapp (edn/read-string message) ch))

  (on-close [{:keys [channels path] :as socketapp} ch close-reason]
    (swap! channels update-in [path] dissoc ch)
    (handle-command socketapp
                    [:leave (find-user-by-channel socketapp ch)] ch)))

(defmethod ig/init-key :back-channeling.websocket/socketapp [_ {:keys [logger path cache]}]
  (map->Socketapp {:logger logger
                   :channels (atom {})
                   :path path
                   :cache cache}))
