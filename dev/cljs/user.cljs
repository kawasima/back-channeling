(ns cljs.user
  (:require [devtools.core :as devtools]
            [figwheel.client :as figwheel]
            [om.core :as om :include-macros true]
            [back-channeling.components.root :refer [root-view]]))

(js/console.info "Starting in development mode")

(enable-console-print!)

(devtools/install!)

(figwheel/start {:websocket-url "ws://localhost:3449/figwheel-ws"})

(def app-state (atom {:boards {}
                      :users #{}
                      :page :board}))

(om/root root-view app-state
         {:target (.getElementById js/document "app")})

(defn log [& args]
  (.apply js/console.log js/console (apply array args)))
