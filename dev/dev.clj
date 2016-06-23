(ns dev
  (:refer-clojure :exclude [test])
  (:require [clojure.repl :refer :all]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as component]
            [duct.generate :as gen]
            [meta-merge.core :refer [meta-merge]]
            [reloaded.repl :refer [system init start stop go reset]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace]]
            [duct.component.figwheel :as figwheel]
            [dev.tasks :refer :all]
            [back-channeling.config :as config]
            [back-channeling.system :as system]))

(def dev-config
  {:app {:middleware [wrap-stacktrace]}
   :figwheel
   {:css-dirs ["resources/public/css"]
    :builds   [{:source-paths ["src/cljs" "dev"]
                :build-options
                {:optimizations :none
                 :main "cljs.user"
                 :asset-path "/js"
                 :output-to  "target/figwheel/back_channeling/public/js/main.js"
                 :output-dir "target/figwheel/back_channeling/public/js"
                 :source-map true
                 :source-map-path "/js"}}]}})

(def config
  (meta-merge config/defaults
              config/environ
              dev-config))

(defn new-system []
  (into (system/new-system config)
        {:figwheel (figwheel/server (:figwheel config))}))

(when (io/resource "local.clj")
  (load "local"))

(gen/set-ns-prefix 'back-channeling)

(reloaded.repl/set-init! new-system)

