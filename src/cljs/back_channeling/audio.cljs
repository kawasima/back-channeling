(ns back-channeling.audio
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :refer [put! <! chan]]))

(set! (.-getUserMedia js/navigator) (or (.-getUserMedia js/navigator)
                                        (.-webkitGetUserMedia js/navigator)
                                        (.-mozGetUserMedia js/navigator)
                                        (.-msGetUserMedia js/navigator)))

(def asked-permission? (atom false))
(def worker-path "https://4dbefa02675a4cdb7fc25d009516b060a84a3b4b.googledrive.com/host/0B6GWd_dUUTT8WjhzNlloZmZtdzA/ffmpeg_asm.js")
(def media-recorder (atom nil))
(def recorded-ch (chan))
(def worker (atom nil))

(defn audio-available? []
  (.-getUserMedia js/navigator))

(defn on-media-success [stream]
  (reset! media-recorder (js/MediaStreamRecorder. stream))
  (set! (.-mimeType @media-recorder) "audio/ogg")
  (set! (.-audioChannels @media-recorder) 1)
  (set! (.-ondataavailable @media-recorder)
        (fn [blob]
          (put! recorded-ch blob))))

(defn start-recording []
  (when-not @asked-permission?
    (.getUserMedia js/navigator
                 (clj->js {:audio true})
                 on-media-success
                 (fn [err]
                   (.error js/console err)))
    (reset! asked-permission? true))
  
  (.start @media-recorder 30000))

(defn stop-recording [callback]
  (.stop @media-recorder)
  (go
    (let [blob (<! recorded-ch)]
      (callback blob))))

(defn create-worker []
  (let [script (str "importScripts('" worker-path "');function print(text) {postMessage({'type': 'stdout', 'data': text});};"
                    "onmessage = function(event) {var message = event.data;if (message.type === 'command') {"
                    "var Module={print:print,printErr:print,files:message.files||[],arguments:message.arguments||[],TOTAL_MEMORY:268435456};"
                    "var result = ffmpeg_run(Module);"
                    "postMessage({'type':'done','data':result});}};")
        blob (.createObjectURL js/URL (js/Blob. (clj->js [script])
                                                (clj->js {:type "application/javascript"})))
        worker (js/Worker. blob)]
    (.revokeObjectURL js/URL blob)
    worker))

(defn wav->ogg [blob callback]
  (let [file-reader (js/FileReader.)]
    (set! (.-onload file-reader)
          (fn []
            (.postMessage @worker
                          (clj->js {:type "command"
                                    :arguments ["-i" "audio.wav"
                                                "-c:a" "vorbis"
                                                "-b:a" "4800k"
                                                "-ac" "2"
                                                "-strict" "experimental" "output.ogg"]
                                    :files [{:data (js/Uint8Array. (.-result file-reader))
                                             :name "audio.wav"}]}))))
    (when-not @worker
      (reset! worker (create-worker)))
    (set! (.-onmessage @worker)
          (fn [event]
            (let [message (.-data event)]
              (condp =  (.-type message)
                "stdout" (println (.-data message))
                "done"
                (callback
                 (js/Blob. (clj->js [(.-data (aget (.-data message) 0))])
                           (clj->js {:type "audio/ogg"})))))))
    (.readAsArrayBuffer file-reader blob)))
