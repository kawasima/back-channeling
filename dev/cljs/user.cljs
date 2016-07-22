(ns cljs.user
  (:require [devtools.core :as devtools]
            [figwheel.client :as figwheel]
            [om.core :as om :include-macros true]
            [back-channeling.components.root :refer [root-view]]))

(js/console.info "Starting in development mode")

(enable-console-print!)

(devtools/install!)

(figwheel/start {:websocket-url "ws://localhost:3449/figwheel-ws"})

(set! js/md (-> (js/markdownit
                 #js {:highlight (fn [s lang]
                                   (when (and lang (.getLanguage js/hljs lang))
                                     (try
                                       (str "<pre class=\"hljs\"><code>"
                                            (-> js/hljs
                                                (.highlight lang s true)
                                                (.-value))
                                            "</code></pre>"))))})
                (.use js/markdownitEmoji)))

(set! (.. js/md -renderer -rules -emoji)
      (fn [token idx]
        (.parse js/twemoji (.-content (aget token idx)))))

(def app-state (atom {:boards {}
                      :users #{}
                      :page :board}))

(om/root root-view app-state
         {:target (.getElementById js/document "app")})

(defn log [& args]
  (.apply js/console.log js/console (apply array args)))
