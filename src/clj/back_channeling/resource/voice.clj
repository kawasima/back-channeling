(ns back-channeling.resource.voice
  (:require [liberator.core :as liberator]
            (back-channeling.boundary [boards :as boards])
            (back-channeling.resource [base :refer [base-resource has-permission?]]))
  (:import [java.util UUID]
           [java.io InputStream]
           [java.nio.file Files Paths CopyOption]
           [java.nio.file.attribute FileAttribute]))

;; 10 MB
(def ^:private max-voice-size (* 10 1024 1024))

(defn- limited-input-stream
  "Wrap an InputStream to throw an exception if more than `limit` bytes are read."
  ^InputStream [^InputStream in limit]
  (let [remaining (atom limit)]
    (proxy [InputStream] []
      (read
        ([]
         (if (<= @remaining 0)
           (throw (ex-info "Voice file exceeds size limit" {:max-bytes limit}))
           (let [b (.read in)]
             (when (>= b 0) (swap! remaining dec))
             b)))
        ([^bytes buf]
         (.read ^InputStream this buf 0 (alength buf)))
        ([^bytes buf off len]
         (let [r @remaining]
           (if (<= r 0)
             (throw (ex-info "Voice file exceeds size limit" {:max-bytes limit}))
             (let [n (.read in buf off (int (min len r)))]
               (when (pos? n) (swap! remaining - n))
               n)))))
      (close [] (.close in)))))

(defn voices-resource [{:keys [datomic]} thread-id]
  (liberator/resource base-resource
   :allowed-methods [:post]
   :malformed? (fn [ctx]
                 (let [content-type (get-in ctx [:request :headers "content-type"])
                       content-length (some-> (get-in ctx [:request :headers "content-length"])
                                              parse-long)]
                   (cond
                     (and content-length (> content-length max-voice-size))
                     [true {:representation {:media-type "application/edn"}
                            :message "Voice file exceeds size limit"}]

                     :else
                     (case content-type
                       "audio/webm" [false {::media-type :audio/webm}]
                       "audio/ogg"  [false {::media-type :audio/ogg}]
                       "audio/wav"  [false {::media-type :audio/wav}]
                       true))))
   :allowed? #(has-permission? % #{:write-thread :write-any-thread})
   :post! (fn [ctx]
            (let [body-stream (limited-input-stream (get-in ctx [:request :body]) max-voice-size)
                  filename (str (.toString (UUID/randomUUID))
                                (case (::media-type ctx)
                                  :audio/webm ".webm"
                                  :audio/ogg  ".ogg"
                                  :audio/wav  ".wav"))
                  path (Paths/get "voices"
                                  (into-array String [(str thread-id) filename]))]
              (Files/createDirectories (.getParent path)
                                       (make-array FileAttribute 0))
              (try
                (Files/copy body-stream path
                            (make-array CopyOption 0))
                (catch clojure.lang.ExceptionInfo e
                  (Files/deleteIfExists path)
                  (throw e)))
              {::filename filename}))
   :handle-created (fn [ctx]
                     {:comment/content (str thread-id "/" (::filename ctx))})))
