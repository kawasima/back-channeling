(ns back-channeling.module.app
  (:require [integrant.core :as ig]
            [duct.core :as core]))

(defmethod ig/init-key :back-channeling.path/prefix [_ options]
  (if (string? options)
    options
    (str "")))

(defmethod ig/init-key :back-channeling.path/asset-path [_ options]
  (if (string? options)
    options
    (str "/js")))

(defmethod ig/init-key :back-channeling.module/app [_ options]
  {:req #{:duct/logger}
   :fn  (fn [config]
          (core/merge-configs
           config
           options
           {:back-channeling.handler/api
            {:datomic   (ig/ref :back-channeling.database/datomic)
             :socketapp (ig/ref :back-channeling.websocket/socketapp)
             :cache     (ig/ref :back-channeling.database/cache)}
            :back-channeling.handler/chat-app
            ^:demote {:datomic   (ig/ref :back-channeling.database/datomic)
                      :prefix    (ig/ref :back-channeling.path/prefix)
                      :env       (ig/ref :duct.core/environment)
                      :login-enabled? true}

            :back-channeling.websocket/socketapp
            ^:demote {:path "/ws"
                      :cache  (ig/ref :back-channeling.database/cache)
                      :logger (ig/ref :duct/logger)}}))})
