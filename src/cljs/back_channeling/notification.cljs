(ns back-channeling.notification
  )

(defn initialize []
  (.requestPermission
   js/Notification
   (fn [status]
     (if (not= (.-premission js/Notification) status)
       (set! (.-premission js/Notification) status)))))

(defn notify [notification]
  (let [notifiation (js/Notification. "")]
    (set! (.-onclick notification)
          (fn []
            (set! (.-href js/location)
                  (str "#/board/" (:board-name notifiaction) "/"
                       (:thread-id notification) "/"
                       (:comment-no notification)))
            (.close notification)))))
