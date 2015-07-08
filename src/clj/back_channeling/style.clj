(ns back-channeling.style
  (:use [garden.core :only [css]]
        [garden.units :only [px em]]))

(def styles
  [[:body :html {:height "100%"}]
   [:body
    [:.login.grid {:height "100%"}
     [:.column {:max-width (px 450)}]]]
   [:img.ui.logo.image {:width (px 240)}]

   [:.preview {:white-space "pre"}]
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
                :min-height (px 180)
                :height "100%"}]
    [:.comment
     [:.text {:white-space "pre"}]]
    [:.comment.selected {:background-color "#f4b3c2"}]]
   [:.comment.curating-block
     [:.ui.basic.buttons {:position "absolute"
                          :right 0}]]
   [:i.citation {:position "fixed"
                 :z-index "30"
                 :margin-left (px -20)
                 :margin-top (px 100)}]
   [:.scroll-pane {:min-height "100%"
                   :max-height "500px"
                   :height "100%"
                   :overflow-y "auto"}]])


(defn build []
  (css {:pretty-pring? false} styles))
