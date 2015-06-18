(ns back-channeling.style
  (:use [garden.core :only [css]]
        [garden.units :only [px em]]))

(def styles
  [[:.ui.page {:height "inherit"}]
   [:.main.content {:min-height "100%"
                    :max-width (px 960)
                    :margin {:left "auto" :right "auto"}
                    :padding {:top (px 80)
                              :left (em 2)
                              :right (em 2)}
                    :background {:color "#fff"}
                    :border {:left "1px solid #ddd"
                             :right "1px solid #ddd"}}]
   [:#timeline-inner {:font-size (px 8)}]
   [:#job-blocks-inner {:height (px 400)}]
   [:#tab-content {:padding (px 10)}]
   [:.ui.menu
    [:#agent-stats.item :#job-stats.item
     {:padding-top (em 0)}
     [:a {:cursor "pointer"}]
     [:.ui.horiontal.statistics :.statistic {:margin {:top (em 0.2)
                                                      :bottom (em 0.2)}}]]]
   [:.ui.cards
    [:.job-detail.card {:width "100%"}
     [:#job-blocks-inner {:height (px 250)}]]]
   [:#job-blocks {:min-height (px 500)}]
   [:.step-view
    [:.item
     [:.content
      [:.log.list {:background {:color "#6f6f6f"}
                   :padding (em 1)
                   :font-family "monospace"
                   :border {:radius (px 3)}
                   :overflow {:y "auto" :x "auto"}
                   :max-width (px 800)
                   :max-height (px 400)}
       [:.item
        [:.content
         [:.description {:color "#dcdccc"}
          [:span {:margin {:right (em 1)}}]
          [:span.date {:color "#dca3a3"}]
          [:pre {:overflow "visible"}]]]]]]]]

   [:.vis.timeline
    [:.item.range {:color "#313131"
                   :background {:color "#abe1fd"}
                   :border {:color "#abe1fd"}}]
    [:.item.range.completed {:color "#3c763d"
                             :background {:color "#adddcf"}
                             :border {:color "#adddcf"}}]
    [:.item.range.failed    {:color "#cd2929"
                             :background {:color "#fed1ab"}
                             :border {:color "#fed1ab"}}]]])


(defn build []
  (css {:pretty-pring? false} styles))
