(ns back-channeling.core
  (:require [om.core :as om :include-macros true])
  (:use [back-channeling.components.root :only [root-view]]))

(.initHighlightingOnLoad js/hljs)
(set! js/md (js/markdownit))

(def app-state (atom {:boards {}
                      :users #{}
                      :page :board}))

(om/root root-view app-state
         {:target (.getElementById js/document "app")})
