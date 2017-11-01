(ns back-channeling.components.comment
  (:require [om.core :as om :include-macros true]
            [sablono.core :as html :refer-macros [html]]
            [back-channeling.components.avatar :refer [avatar]])
  (:use [back-channeling.comment-helper :only [format-plain]])
  (:import [goog.i18n DateTimeFormat]))

(def date-format-m  (DateTimeFormat. goog.i18n.DateTimeFormat.Format.MEDIUM_DATETIME
                                     (aget goog.i18n (str "DateTimeSymbols_" (.-language js/navigator)))))

(defn random-string [n]
  (->> (repeatedly #(rand-nth "0123456789abcdefghijklmnopqrstuvwxyz"))
       (take n)
       (reduce str)))

(defn comment-view
  [comment owner {:keys [thread board-name comment-attrs show-reactions?]
                  :or   {show-reactions? false}}]
  (reify
    om/IRenderState
    (render-state [_ {:keys [selected?]}]
      (html
       [:div.comment (merge {:data-comment-no (:comment/no comment)
                             :key (str (:db/id thread) "-"(:comment/no comment))}
                            comment-attrs
                            (when selected? {:class "selected"}))
        (om/build avatar (get-in comment [:comment/posted-by]))
        [:div.content (when-not (:comment/public? comment) {:class "deleted"})
         [:a.number (:comment/no comment)] ": "
         [:a.author (get-in comment [:comment/posted-by :user/name])]
         [:div.metadata
          [:span.date (.format date-format-m (get-in comment [:comment/posted-at]))]]
         [:div.text (case (get-in comment [:comment/format :db/ident])
                      :comment.format/markdown
                      {:key (str "markdown-" (random-string 16))
                       :dangerouslySetInnerHTML {:__html (.render js/md (:comment/content comment))}}
                      :comment.format/voice
                      [:audio {:controls true
                               :src (str "/voice/" (:comment/content comment))}]

                      (format-plain (:comment/content comment)
                                    :thread-id (:db/id thread)
                                    :board-name board-name))]]
        (when show-reactions?
          (when-let [reactions (not-empty (:comment/reactions comment))]
            [:div.content
             [:div.text
              (for [reaction reactions]
                [:div.ui.tiny.pointing.basic.label
                 (get-in reaction [:comment-reaction/reaction :reaction/label])])]]))]))))
