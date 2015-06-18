(ns back-channeling.core
  (:require [om.core :as om :include-macros true])
  (:use [job-streamer.console.components.root :only [root-view]]))


(def app-state (atom {}))

(om/root root-view app-state
         {:target (.getElementById js/document "app")})



