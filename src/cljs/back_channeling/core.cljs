(ns back-channeling.core
  (:require [reagent.dom :as rdom]
            [re-frame.core :as rf]
            [back-channeling.events :as events]
            [back-channeling.subs]
            [back-channeling.fx]
            [back-channeling.routes :as routes]
            [back-channeling.components.root :refer [root-view]]))

(.highlightAll js/hljs)
(set! js/md (js/markdownit))

(defn ^:dev/after-load mount-root []
  (rf/clear-subscription-cache!)
  (rdom/render [root-view]
               (.getElementById js/document "app")))

(defn init []
  (rf/dispatch-sync [::events/initialize])
  (routes/init!)
  (mount-root))

(init)
