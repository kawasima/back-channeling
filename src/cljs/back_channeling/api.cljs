(ns back-channeling.api
  (:require [clojure.browser.net :as net]
            [goog.events :as events]
            [goog.net.ErrorCode]
            [goog.net.EventType]
            [cljs.reader :refer [read-string]]))

(defn handle-each-type [handler response xhrio]
  (if (fn? handler)
    (handler response)
    (.error js/console
            (str (goog.net.ErrorCode/getDebugMessage (.getLastErrorCode xhrio))
                 " from "
                 (.getLastUri xhrio)))))

(defn request
  ([path]
   (request path :GET nil {}))
  ([path options]
   (request path :GET nil options))
  ([path method options]
   (request path method nil options))
  ([path method body {:keys [handler error-handler format]}]
   (let [xhrio (net/xhr-connection)]
     (when handler
       (events/listen xhrio goog.net.EventType/SUCCESS
                      (fn [_]
                        (let [res (read-string (.getResponseText xhrio))]
                          (handler res)))))
     (when error-handler
       (events/listen xhrio goog.net.EventType/ERROR
                      (fn [_]
                        (let [res (read-string (.getResponseText xhrio))]
                          (cond
                            (fn? error-handler)
                            (error-handler res xhrio)

                            (map? error-handler)
                            (condp = (.getLastErrorCode xhrio)
                              goog.net.ErrorCode/ACCESS_DENIED  (handle-each-type (:access-denied error-handler) res xhrio)
                              goog.net.ErrorCode/FILE_NOT_FOUND (handle-each-type (:file-not-found error-handler) res xhrio)
                              goog.net.ErrorCode/CUSTOM_ERROR   (handle-each-type (:custom-error error-handler) res xhrio)
                              goog.net.ErrorCode/EXCEPTION      (handle-each-type (:exception error-handler) res xhrio)
                              goog.net.ErrorCode/HTTP_ERROR     (handle-each-type (:http-error error-handler) res xhrio)
                              goog.net.ErrorCode/ABORT          (handle-each-type (:abort error-handler) res xhrio)
                              goog.net.ErrorCode/TIMEOUT        (handle-each-type (:timeout error-handler) res xhrio)
                              goog.net.ErrorCode/OFFLINE        (handle-each-type (:offline error-handler) res xhrio)))))))
     (let [prefix (some-> js/document
                          (.querySelector "meta[property='bc:prefix']")
                          (.getAttribute "content"))]
       (.send xhrio (str prefix path) (.toLowerCase (name method))
              (when body
                (cond
                  (string? body) body
                  (instance? js/Blob body) body
                  :else (pr-str body)))
              (-> (case format
                    :xml {:content-type "application/xml"}
                    :ogg {:content-type "audio/ogg"}
                    :wav {:content-type "audio/wav"}
                    {:content-type "application/edn"})
                  (assoc :accept "application/edn")
                  clj->js))))))
