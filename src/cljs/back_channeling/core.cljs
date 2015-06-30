(ns back-channeling.core
  (:require [om.core :as om :include-macros true])
  (:use [back-channeling.components.root :only [root-view]]))


(def app-state (atom {:boards {}}))

(om/root root-view app-state
         {:target (.getElementById js/document "app")})



