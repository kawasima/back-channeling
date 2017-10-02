(ns back-channeling.resource.voice
  (:require [liberator.core :as liberator]
            (back-channeling.boundary [boards :as boards]))
  (:import [java.util UUID]
           [java.nio.file Files Paths CopyOption]
           [java.nio.file.attribute FileAttribute]))

(defn voices-resource [{:keys [datomic]} thread-id]
  (liberator/resource
   :available-media-types ["application/edn" "application-json"]
   :allowed-methods [:post]
   :malformed? (fn [ctx]
                 (let [content-type (get-in ctx [:request :headers "content-type"])]
                   (case content-type
                     "audio/webm" [false {::media-type :audio/webm}]
                     "audio/ogg"  [false {::media-type :audio/ogg}]
                     "audio/wav"  [false {::media-type :audio/wav}]
                     true)))
   :allowed? true ;; Authorization
   :post! (fn [ctx]
            (let [body-stream (get-in ctx [:request :body])
                  filename (str (.toString (UUID/randomUUID))
                                (case (::media-type ctx)
                                  :audio/webm ".webm"
                                  :audio/ogg  ".ogg"
                                  :audio/wav  ".wav"))
                  path (Paths/get "voices"
                                  (into-array String [(str thread-id) filename]))]
              (Files/createDirectories (.getParent path)
                                       (make-array FileAttribute 0))
              (Files/copy body-stream path
                          (make-array CopyOption 0))
              {::filename filename}))
   :handle-created (fn [ctx]
                     {:comment/content (str thread-id "/" (::filename ctx))})))
