(ns back-channeling.components.curation
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan timeout]]
            [back-channeling.api :as api]
            [back-channeling.components.avatar :refer [avatar]])
  (:import [goog.i18n DateTimeFormat]))

(def date-format-m  (DateTimeFormat. goog.i18n.DateTimeFormat.Format.MEDIUM_DATETIME
                                     (aget goog.i18n (str "DateTimeSymbols_" (.-language js/navigator)))))

(defn open-thread [thread owner]
  (api/request (str "/api/thread/" (:db/id thread))
               {:handler (fn [response]
                           (om/set-state! owner [:thread :thread/comments] (:thread/comments response)))}))

(defn save-article [article]
  )

(defcomponent editorial-space-view [curating-block owner]
  (init-state [_]
    {:editing? true})
  (render-state [_ {:keys [editing?]}]
    (println editing?)
    (html
     (if editing?
       [:div.ui.form
        [:div.field
         [:textarea
          {:value (:comment/content curating-block)
           :on-change (fn [e]
                        (let [content (.. (om/get-node owner) (querySelector "textarea") -value)]
                          (om/transact! curating-block #(assoc % :comment/content content))))
           :on-key-up (fn [e]
                        (when (and (= (.-which e) 0x0d) (.-ctrlKey e))
                          (om/set-state! owner :editing? false)))}]]]
       [:div {:dangerouslySetInnerHTML {:__html (js/marked (str (:comment/content curating-block)))}
              :on-click (fn [e] (om/set-state! owner :editing? true)) }]))))

(defn generate-markdown [curating-blocks]
  (->> curating-blocks
       (map #(if (= (:comment/format %) :comment.format/markdown)
               (:comment/content %)
               (str "```\n" (:comment/content %) "\n```\n")))
       (clojure.string/join "\n\n")))

(defcomponent curation-page [curating-blocks owner {:keys [user]}]
  (init-state [_]
    {:selected-thread-comments #{}
     :article {}
     :editorial-space {:db/id 0
                       :comment/format :comment.format/markdown
                       :comment/content ""
                       :comment/posted-by user}})
  
  (will-mount [_]
    (open-thread (om/get-state owner :thread) owner))

  (did-mount [_]
    (when-let [markdown-btn (.. (om/get-node owner) (querySelector "button.markdown.button"))]
      (let [clipboard (js/ZeroClipboard. markdown-btn)]
        (.on clipboard "ready"
             (fn [_]
               (.on clipboard "copy"
                    (fn [e]
                      (.. e -clipboardData (setData "text/plain"
                                                    (generate-markdown @curating-blocks))))))))))
  
  (render-state [_ {:keys [selected-thread-comments editorial-space thread article]}]
    (html
     [:div.curation.full.height.content
      [:div.ui.full.height.grid
       [:div.full.height.row
        [:div.seven.wide.full.height.column
         [:div.scroll-pane
          [:div.ui.thread.comments
           [:h3.ui.dividing.header (:thread/title thread)]
           [:div.comment {:on-click (fn [_]
                                      (om/update-state! owner :selected-thread-comments
                                                        #(if ((om/get-state owner :selected-thread-comments) 0)
                                                           (disj % 0) (conj % 0))))}
            [:div.content
             [:div.ui.message (when (selected-thread-comments 0) {:class "red"}) "Editorial space"]]]
           (for [comment (:thread/comments thread)]
             [:div.comment {:on-click (fn [_]
                                        (if ((om/get-state owner :selected-thread-comments) (:db/id comment))
                                          (om/update-state! owner :selected-thread-comments #(disj % (:db/id comment)))
                                          (om/update-state! owner :selected-thread-comments #(conj % (:db/id comment)))))
                            :class (if (selected-thread-comments (:db/id comment)) "selected" "")}
              (om/build avatar (get-in comment [:comment/posted-by :user/email]))
              [:div.content
               [:a.number (:comment/no comment)] ": "
               [:a.author (get-in comment [:comment/posted-by :user/name])]
               [:div.metadata
                [:span.date (.format date-format-m (get-in comment [:comment/posted-at]))]]
               [:div.text (case (get-in comment [:comment/format :db/ident])
                            :comment.format/markdown {:dangerouslySetInnerHTML {:__html (js/marked (:comment/content comment))}}
                            (:comment/content comment))]]])]]]
        
        [:div.column
         (when (not-empty selected-thread-comments)
           [:i.citation.huge.arrow.circle.outline.right.icon
            {:on-click (fn [_]
                         (om/transact! curating-blocks
                                       (fn [curating-blocks]
                                         (into curating-blocks
                                               (->> (om/get-state owner :selected-thread-comments)
                                                    (map (fn [comment-id]
                                                           (->> (conj (:thread/comments thread) editorial-space)
                                                                (filter #(= (:db/id %) comment-id))
                                                                first)))))))
                         (om/set-state! owner :selected-thread-comments #{}))}])]
        
        [:div.eight.wide.full.height.column
         [:div.ui.input (when (= (count curating-blocks) 0)
                          {:style {:visibility "hidden"}})
          [:input {:type "text" :name "article-name"
                   :placeholder "Curation name"
                   :value (:article/name article)
                   :on-change (fn [e]
                                (om/set-state! owner [:article :article/name]
                                               (.. (om/get-node owner) (querySelector "[name='article-name']") -value)))}]
          [:button.ui.olive.basic.markdown.button
           [:i.paste.icon]
           "Markdown"]
          [:button.ui.primary.button
           {:on-click (fn [_]
                        (api/request (str "/api/curations") :POST
                                     (-> (om/get-state owner :article)
                                         (assoc :article/blocks @curating-blocks
                                                :article/curator user))
                                     {:handler (fn [response]
                                                 (om/set-state! owner [:article :db/id] (:db/id response)))}))}
           [:i.save.icon] "Save"]]
         [:div.scroll-pane
          [:div.ui.comments
           (map-indexed
            (fn [index curating-block]
              (list
               [:div.ui.divider]
               [:div.comment.curating-block
                [:div.ui.mini.basic.icon.buttons
                 [:button.ui.button
                  {:on-click (fn [_]
                               (when (> index 0)
                                 (om/transact! curating-blocks
                                               (fn [curating-blocks]
                                                 (assoc curating-blocks
                                                        (dec index) (get curating-blocks index)
                                                        index (get curating-blocks (dec index)))))))}
                  [:i.caret.up.icon]]
                 [:button.ui.button
                  {:on-click (fn [_]
                               (when (< index (dec (count @curating-blocks)))
                                 (om/transact! curating-blocks
                                               (fn [curating-blocks]
                                                 (assoc curating-blocks
                                                        (inc index) (get curating-blocks index)
                                                        index (get curating-blocks (inc index)))))))}
                  [:i.caret.down.icon]]
                 [:button.ui.button
                  {:on-click (fn [_]
                               (om/transact! curating-blocks
                                             (fn [curating-blocks]
                                               (vec (concat (take index curating-blocks)
                                                            (drop (inc index) curating-blocks))))))}
                  [:i.close.icon]]]
                [:div.metadata
                 [:span (get-in curating-block [:comment/posted-by :user/name]) "(" (.format date-format-m (get-in curating-block [:comment/posted-at] (js/Date.))) ")"]]
                [:div.text
                 (if (= (:db/id curating-block) 0)
                   (om/build editorial-space-view curating-block)
                   (case (get-in curating-block [:comment/format :db/ident])
                     :comment.format/markdown {:dangerouslySetInnerHTML {:__html (js/marked (:comment/content curating-block))}}
                     (:comment/content curating-block)))]]))
            curating-blocks)]]]]]])))
