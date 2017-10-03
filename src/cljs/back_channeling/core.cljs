(ns back-channeling.core
  (:require [om.core :as om :include-macros true])
  (:use [back-channeling.components.root :only [root-view]]))

(.initHighlightingOnLoad js/hljs)
(set! js/md (js/markdownit))

(def app-state (atom {:boards []
                      :board {:name "default"
                              :threads []}
                      :threads {}
                      :users #{}
                      :page :boards}))

(om/root root-view app-state
         {:target (.getElementById js/document "app")
          :shared {:prefix (some-> js/document
                                   (.querySelector "meta[property='bc:prefix']")
                                   (.getAttribute "content"))}})
