(ns back-channeling.audio
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :refer [put! <! chan promise-chan]]))

(def asked-permission? (atom false))
(def media-recorder (promise-chan))
(def recorded-ch (chan))

(defn audio-available? []
  (exists? js/navigator.mediaDevices))

(defn on-media-success [stream]
  (let [mime-type (cond
                    (and (exists? js/MediaRecorder)
                         (.isTypeSupported js/MediaRecorder "audio/webm;codecs=opus"))
                    "audio/webm;codecs=opus"

                    (and (exists? js/MediaRecorder)
                         (.isTypeSupported js/MediaRecorder "audio/ogg;codecs=opus"))
                    "audio/ogg;codecs=opus"

                    :else "audio/webm")
        chunks (atom [])
        rec (js/MediaRecorder. stream (clj->js {:mimeType mime-type}))]
    (set! (.-ondataavailable rec)
          (fn [e]
            (when (> (.-size (.-data e)) 0)
              (swap! chunks conj (.-data e)))))
    (set! (.-onstop rec)
          (fn [_]
            (let [blob (js/Blob. (clj->js @chunks)
                                 (clj->js {:type mime-type}))]
              (reset! chunks [])
              (put! recorded-ch blob))))
    (put! media-recorder rec)))

(defn start-recording []
  (when-not @asked-permission?
    (-> (.getUserMedia js/navigator.mediaDevices (clj->js {:audio true}))
        (.then on-media-success)
        (.catch (fn [err] (.error js/console err))))
    (reset! asked-permission? true))
  (go
    (.start (<! media-recorder))))

(defn stop-recording [callback]
  (go
    (.stop (<! media-recorder))
    (let [blob (<! recorded-ch)]
      (callback blob))))
