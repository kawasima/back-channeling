(ns back-channeling.notification
  (:use [back-channeling.components.avatar :only [avatar-url]]))

(def initialized? (atom (= (.. js/Notification -permission) "granted")))

(defn initialize []
  (when-not @initialized?
    (.requestPermission js/Notification)
    (reset! initialized? true)))

(defn show [message]
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
