(ns back-channeling.component-helper)

(defn make-click-outside-fn [inside event-handler]
  (fn [e]
    (loop [node (.-target e)]
      (if-let [parent (.-parentNode node)]
        (when (not= inside node)
          (recur parent))
        (event-handler e)))))
