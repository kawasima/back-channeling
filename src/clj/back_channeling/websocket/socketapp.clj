(ns back-channeling.websocket.socketapp
  (:require [integrant.core :as ig]
            [duct.logger :refer [log]]
            [clojure.edn :as edn]
            (back-channeling.boundary [tokens :as tokens]))
  (:import [io.undertow.websockets.core WebSockets WebSocketCallback]))


(defprotocol ISendMessage
  (broadcast-message [this message])
  (multicast-message [this message users])
  (board-multicast-message [this message board-name])
  (on-connect [this exchange channel])
  (on-message [this channel message])
  (on-close   [this channel close-reason]))

;; Channel data structure:
;; {path {channel {:user {:user/name "..." :user/email "..."}
;;                 :board "board-name-or-nil"}}}
;; :user is nil until :auth command is received.

(defn find-users [{:keys [channels path]}]
  (->> (get @channels path)
       vals
       (keep :user)
       (apply hash-set)))

(defn find-user-by-channel [{:keys [channels path]} ch]
  (get-in @channels [path ch :user]))

(defn find-user-by-name [{:keys [channels path]} user-name]
  (->> (get @channels path)
       vals
       (keep :user)
       (filter #(= (:user/name %) user-name))
       first))

(defmulti handle-command (fn [socketapp msg ch] (first msg)))

(defmethod handle-command :auth [{:keys [channels path cache] :as socketapp} [_ {:keys [token]}] ch]
  (when-let [user (some-> (tokens/auth-by cache token)
                          (select-keys [:user/name :user/email]))]
    (swap! channels assoc-in [path ch :user] user)
    (broadcast-message socketapp [:join user])))

(defmethod handle-command :subscribe-board [{:keys [channels path]} [_ {board-name :board/name}] ch]
  (when (and board-name (get-in @channels [path ch :user]))
    (swap! channels assoc-in [path ch :board] board-name)))

;; :leave is only dispatched internally from on-close — derive user from
;; the channel data, never trust client-supplied fields.
(defmethod handle-command :leave [socketapp [_ message] ch]
  (when message
    (broadcast-message socketapp
                       [:leave (select-keys message [:user/name :user/email])])))

(defmethod handle-command :call [socketapp [_ message] ch]
  (multicast-message socketapp
                     [:call message]
                     (:to message)))

(defrecord Socketapp [channels path cache logger])

(defn- make-ws-callback [logger user]
  (proxy [WebSocketCallback] []
    (complete [channel context])
    (onError [channel context throwable]
      (log logger :warn ::ws-send-error {:user user :error throwable}))))

(extend-type Socketapp
  ISendMessage
  (broadcast-message [{:keys [channels path logger]} message]
    (doseq [[channel {:keys [user]}] (get @channels path)]
      (when user
        (WebSockets/sendText (pr-str message) channel
                             (make-ws-callback logger user)))))

  (multicast-message [{:keys [channels path logger]} message users]
    (doseq [[channel {:keys [user]}] (get @channels path)]
      (when (and user (users user))
        (WebSockets/sendText (pr-str message) channel
                             (make-ws-callback logger user)))))

  (board-multicast-message [{:keys [channels path logger]} message board-name]
    (doseq [[channel {:keys [user board]}] (get @channels path)]
      (when (and user (= board board-name))
        (WebSockets/sendText (pr-str message) channel
                             (make-ws-callback logger user)))))

  (on-connect [{:keys [channels path logger] :as socketapp} exchange channel]
    ;; Store channel as unauthenticated. Client must send :auth message.
    (swap! channels assoc-in [path channel] {:user nil :board nil})
    ;; Close channel if not authenticated within 10 seconds
    (future
      (Thread/sleep 10000)
      (when (and (get-in @channels [path channel])
                 (nil? (get-in @channels [path channel :user])))
        (log logger :info ::auth-timeout {:channel channel})
        (try
          (.sendClose channel 1008 "Authentication timeout")
          (catch Exception _)))))

  (on-message [{:keys [channels path] :as socketapp} ch message]
    (let [parsed (edn/read-string message)
          cmd (first parsed)
          authenticated? (get-in @channels [path ch :user])]
      ;; :auth only from unauthenticated; :leave only from on-close (internal)
      (when (and (not= cmd :leave)
                 (or (= cmd :auth) authenticated?))
        (handle-command socketapp parsed ch))))

  (on-close [{:keys [channels path] :as socketapp} ch close-reason]
    (let [user (find-user-by-channel socketapp ch)]
      (swap! channels update-in [path] dissoc ch)
      (when user
        (handle-command socketapp [:leave user] ch)))))

(defmethod ig/init-key :back-channeling.websocket/socketapp [_ {:keys [logger path cache]}]
  (map->Socketapp {:logger logger
                   :channels (atom {})
                   :path path
                   :cache cache}))
