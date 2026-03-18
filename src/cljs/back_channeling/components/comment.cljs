(ns back-channeling.components.comment
  (:require [clojure.string :as string]
            [back-channeling.components.avatar :refer [avatar]]
            [back-channeling.comment-helper :refer [format-plain escape-regex]]
            [back-channeling.format-helper :refer [date-format-medium]]))

(defn random-string [n]
  (->> (repeatedly #(rand-nth "0123456789abcdefghijklmnopqrstuvwxyz"))
       (take n)
       (reduce str)))

(defn- html-escape [s]
  (-> s
      (string/replace "&" "&amp;")
      (string/replace "<" "&lt;")
      (string/replace ">" "&gt;")
      (string/replace "\"" "&quot;")))

(defn- highlight-html [html query]
  (if (and query (not (string/blank? query)))
    (let [escaped (escape-regex query)
          safe-query (html-escape query)
          pattern (js/RegExp. (str "(?<=>)([^<]*?)(" escaped ")") "gi")]
      (.replace html pattern (str "$1<mark style=\"background-color:#fff3cd\">" safe-query "</mark>")))
    html))

(defn comment-view [{:keys [app thread comment comment-attrs show-reactions? selected? search-highlight]
                     :or {show-reactions? false}}]
  [:div.comment (merge {:data-comment-no (:comment/no comment)
                        :key (str (:db/id thread) "-" (:comment/no comment))}
                       comment-attrs
                       (when selected? {:class "selected"}))
   [avatar (get-in comment [:comment/posted-by])]
   [:div.content (when-not (:comment/public? comment) {:class "deleted"})
    [:a.number (:comment/no comment)] ": "
    [:a.author (get-in comment [:comment/posted-by :user/name])]
    [:div.metadata
     [:span.date (.format date-format-medium (get-in comment [:comment/posted-at]))]]
    [:div.text (case (get-in comment [:comment/format :db/ident])
                 :comment.format/markdown
                 {:key (str "markdown-" (random-string 16))
                  :dangerouslySetInnerHTML {:__html (highlight-html
                                                     (.render js/md (:comment/content comment))
                                                     search-highlight)}}
                 :comment.format/voice
                 [:audio {:controls true
                          :src (str "/voice/" (:comment/content comment))}]

                 (format-plain (:comment/content comment)
                               :thread-id (:db/id thread)
                               :board-name (get-in app [:board :board/name])
                               :search-highlight search-highlight))]]
   (when show-reactions?
     (when-let [reactions (not-empty (:comment/reactions comment))]
       [:div.content
        [:div.text
         (for [reaction reactions]
           ^{:key (str "r-" (:db/id reaction))}
           [:div.ui.tiny.pointing.basic.label
            (get-in reaction [:comment-reaction/reaction :reaction/label])])]]))])
