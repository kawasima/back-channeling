(ns back-channeling.notification
  (:use [back-channeling.components.avatar :only [avatar-url]]))

(def initialize? (atom false))

(defn initialize []
  (.requestPermission js/Notification)
  (reset! initialize? true))

(defn show [message]
  (when @initialize? (initialize))
  (let [notification (js/Notification. (str "From " (get-in message [:comment/posted-by :user/name]) " @BackChanneling") 
                                       (clj->js {:iconUrl (avatar-url (:comment/posted-by message))
                                                 :icon (avatar-url (:comment/posted-by message))
                                                 :body (:comment/content message)}))]
    (set! (.-onclick notification)
          (fn []
            (set! (.-href js/location)
                  (str "#/board/" (:board/name message) "/"
                       (:thread/id message) "/"
                       (:comment/no message)))
            (.close notification)))))
