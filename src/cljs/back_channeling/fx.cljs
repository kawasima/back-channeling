(ns back-channeling.fx
  (:require [re-frame.core :as rf]
            [back-channeling.api :as api]
            [back-channeling.socket :as socket]
            [back-channeling.notification :as notification]))

;; -- HTTP request effect -----------------------------------------------

(rf/reg-fx
 :http
 (fn [{:keys [path method body format handler error-handler]}]
   (api/request path (or method :GET) body
                (cond-> {}
                  handler       (assoc :handler (fn [response] (rf/dispatch (handler response))))
                  error-handler (assoc :error-handler (fn [response xhrio] (rf/dispatch (error-handler response xhrio))))
                  format        (assoc :format format)))))

;; Variant that calls handler directly (not dispatching)
(rf/reg-fx
 :http-raw
 (fn [{:keys [path method body format handler error-handler]}]
   (api/request path (or method :GET) body
                (cond-> {}
                  handler       (assoc :handler handler)
                  error-handler (assoc :error-handler error-handler)
                  format        (assoc :format format)))))

;; -- WebSocket effects -------------------------------------------------

(rf/reg-fx
 :ws-open
 (fn [{:keys [url on-open on-close on-message]}]
   (socket/open url
                :on-open on-open
                :on-close on-close
                :on-message on-message)))

(rf/reg-fx
 :ws-send
 (fn [{:keys [command message]}]
   (socket/send command message)))

;; -- Navigation effect -------------------------------------------------

(rf/reg-fx
 :navigate
 (fn [hash]
   (set! (.-href js/location) hash)))

;; -- Document title effect ---------------------------------------------

(rf/reg-fx
 :set-title
 (fn [title]
   (set! (.-title js/document) title)))

;; -- Browser notification effect ---------------------------------------

(rf/reg-fx
 :notify
 (fn [data]
   (when-not (.hasFocus js/document)
     (set! (.-title js/document) "* Back Channeling"))
   (notification/show data)))

;; -- Scroll to comment effect ------------------------------------------

(rf/reg-fx
 :scroll-to-comment
 (fn [comment-no]
   (when-let [comment-dom (.. js/document -body
                               (querySelector (str "[data-comment-no='" comment-no "']")))]
     (.scrollTo js/window 0
                (- (+ (.-scrollY js/window)
                      (some->> (.getBoundingClientRect comment-dom) (.-top)))
                   200)))))
