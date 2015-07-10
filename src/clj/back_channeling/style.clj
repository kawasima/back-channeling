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
   [:.curation.content {:min-height "100%"
                       :max-width "90%"
                       :margin {:left "auto" :right "auto"}
                       :padding {:top (px 80)
                                 :left (em 2)
                                 :right (em 2)}
                       :border {:left "1px solid #ddd"
                                :right "1px solid #ddd"}}]
   [:.ui.thread.comments {:max-width "initial"
                          :position "relative"}
    [:a.curation.link {:position "absolute"
                       :top 0
                       :right 0}]
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
                   :overflow-y "auto"}]
   [:div.table.container {:position "relative"
                          :padding-top (px 37)
                          :background-color "#f9fafb"}]
   [:div.tbody.container {:overflow-y "auto"
                          :height (px 200)}
    [:table.ui.table {:width "100%"}
     [:th {:height 0 :line-height 0
           :padding {:top 0 :bottom 0}
           :color "transparent" :border "none" :white-space "nowrap"}
      [:div {:position "absolute"
             :background "transparent"
             :top 0
             :cursor "pointer"
             :color "rgba(0,0,0,.87)"
             :padding "9px 25px"
             :line-height "normal"}]]]]
   [:.account-type {:cursor "pointer"}
    [:&.on {:fill "#aacf53"}]
    [:&.off {:fill "#e0e0e0"}
     [:&:hover {:fill "#aacf53"}]]]])


(defn build []
  (css {:pretty-pring? false} styles))
