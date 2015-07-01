(ns back-channeling.style
  (:use [garden.core :only [css]]
        [garden.units :only [px em]]))

(def styles
  [[:body :html {:height "100%"}]
   [:body
    [:.login.grid {:height "100%"}
     [:.column {:max-width (px 450)}]]]

   [:.main.content {:min-height "100%"
                    :max-width (px 960)
                    :margin {:left "auto" :right "auto"}
                    :padding {:top (px 80)
                              :left (em 2)
                              :right (em 2)}
                    :background {:color "#fff"}
                    :border {:left "1px solid #ddd"
                             :right "1px solid #ddd"}}]
   [:.ui.thread.comments {:max-width "initial"}
    [:.preview {:border {:style "dotted"
                         :width "thin"
                         :color "#e0e0e0"}
                :padding (px 8)
                :position "relative"
                :height "100%"}]]])


(defn build []
  (css {:pretty-pring? false} styles))
