(ns back-channeling.core
  (:require [om.core :as om :include-macros true]
            [cljs.core.async :refer [chan]])
  (:use [back-channeling.components.root :only [root-view]]))

(.initHighlightingOnLoad js/hljs)
(set! js/md (js/markdownit))

(def app-state (atom {:boards []
                      :board {}
                      :threads {}
                      :users #{}
                      :page {:type :initializing}}))

(om/root root-view app-state
         {:target (.getElementById js/document "app")
          :shared {:prefix (some-> js/document
                                   (.querySelector "meta[property='bc:prefix']")
                                   (.getAttribute "content"))
                   :msgbox (chan)}})
