(ns back-channeling.components.curation
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan timeout]]
            [bouncer.core :as b]
            [bouncer.validators :as v]
            [back-channeling.api :as api]
            [back-channeling.components.avatar :refer [avatar]]
            [back-channeling.components.comment :refer [comment-view]])
  (:import [goog.i18n DateTimeFormat]))

(def date-format-m  (DateTimeFormat. goog.i18n.DateTimeFormat.Format.MEDIUM_DATETIME
                                     (aget goog.i18n (str "DateTimeSymbols_" (.-language js/navigator)))))

(defn open-thread [thread owner]
  (when-let [thread-id (:db/id thread)]
    (api/request (str "/api/thread/" thread-id)
                 {:handler (fn [response]
                           (om/set-state! owner [:thread :thread/comments] (:thread/comments response)))})))

(defcomponent editorial-space-view [curating-block owner {save-fn :save-fn}]
  (init-state [_]
    {:editing? true})
  (render-state [_ {:keys [editing? error-map content]}]
    (html
     (if editing?
       [:div.ui.form
        [:div.field (when (:content error-map)
                      {:class "error"})
         [:textarea
          {:value content
           :placeholder "Input content and press Ctrl+Enter to save."
           :on-change (fn [e]
                        (om/set-state! owner :content
                                       (.. (om/get-node owner) (querySelector "textarea") -value)))
           :on-key-up (fn [e]
                        (when (and (= (.-which e) 0x0d) (.-ctrlKey e))
                          (let [content (om/get-state owner :content)
                                [result map] (b/validate {:content content} :content v/required)]
                            (if result
                              (om/set-state! owner :error-map (:bouncer.core/errors map))
                              (do
                                (save-fn content)
                                (om/set-state! owner :editing? false))))))}]]]
       [:div {:on-click (fn [e] (om/set-state! owner :editing? true))} content]))))

(defn generate-markdown [curating-blocks]
  (->> curating-blocks
       (map #(case (get-in % [:curating-block/format :db/ident])
               :curating-block.format/markdown
               (:curating-block/content %)

               :curating-block.format/voice
               (str "\n[" (get-in % [:curating-block/posted-by :user/name]) " said](" (:curating-block/content %) ")\n")

               (str "```\n" (:curating-block/content %) "\n```\n")))
       (clojure.string/join "\n\n")))

(defcomponent article-page [article owner {:keys [user]}]
  (init-state [_]
    {:selected-thread-comments #{}
     :editing-article @article
     :editorial-space {:db/id 0
                       :comment/format {:db/ident :comment.format/plain}
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
                      (let [blocks (om/get-state owner [:editing-article :article/blocks])]
                        (.. e -clipboardData (setData "text/plain"
                                                    (generate-markdown blocks)))))))))))

  (render-state [_ {:keys [selected-thread-comments editorial-space thread editing-article error-map]}]
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
             (om/build comment-view comment
                       {:state {:selected? (selected-thread-comments (:db/id comment))}
                        :opts {:thread thread
                               :comment-attrs
                               {:on-click (fn [_]
                                            (if ((om/get-state owner :selected-thread-comments) (:db/id comment))
                                              (om/update-state! owner :selected-thread-comments #(disj % (:db/id comment)))
                                              (om/update-state! owner :selected-thread-comments #(conj % (:db/id comment)))))
                                :class (if (selected-thread-comments (:db/id comment)) "selected" "")}}
                        :react-key (str "comment-" (:comment/no comment))}))]]]

        [:div.column
         (when (not-empty selected-thread-comments)
           [:i.citation.huge.arrow.circle.outline.right.icon
            {:on-click (fn [_]
                         (om/update-state! owner [:editing-article :article/blocks]
                                           (fn [blocks]
                                             (into blocks
                                                   (->> (om/get-state owner :selected-thread-comments)
                                                        (map (fn [comment-id]
                                                               (->> (conj (:thread/comments thread) editorial-space)
                                                                    (filter #(= (:db/id %) comment-id))
                                                                    first)))
                                                        (map (fn [comment]
                                                               (into {} (for [[k v] comment]
                                                                          [(keyword "curating-block" (name k)) v]))))
                                                        (map (fn [block]
                                                               (update-in block [:curating-block/format :db/ident]
                                                                          #(keyword "curating-block.format" (name %)))))))))
                         (om/set-state! owner :selected-thread-comments #{}))}])]

        [:div.eight.wide.full.height.column
         [:div.ui.input (merge (when (:article/name error-map) {:class "error"})
                               (when (= (count (:article/blocks editing-article)) 0)
                                 {:style {:visibility "hidden"}}))
          [:input {:type "text" :name "article-name"
                   :placeholder "Article name"
                   :value (:article/name editing-article)
                   :on-change (fn [e]
                                (om/update-state!
                                 owner
                                 (fn [state]
                                   (-> state
                                       (assoc-in [:editing-article :article/name]
                                                 (.. (om/get-node owner)
                                                     (querySelector "[name='article-name']")
                                                     -value))
                                       (update-in [:error-map] dissoc :article/name)))))}]
          [:button.ui.olive.basic.markdown.button
           [:i.paste.icon]
           "Markdown"]
          [:button.ui.primary.button
           {:on-click (fn [_]
                        (let [article (om/get-state owner :editing-article)
                              [result map] (b/validate article
                                                       :article/name v/required
                                                       :article/blocks [[v/every #(not (or (nil? (:curating-block/content %))
                                                                                           (empty? (:curating-block/content %))))
                                                                         :message "All editable spaces must be saved."]])]
                          (if result
                            (om/set-state! owner :error-map (:bouncer.core/errors map))
                            (if-let [id (:db/id article)]
                              (api/request (str "/api/article/" id) :PUT
                                     (-> (om/get-state owner :editing-article)
                                         (assoc :article/curator user :article/thread (:db/id thread)))
                                     {:handler (fn [response]
                                                 (om/set-state! owner [:editing-article :db/id] (:db/id article)))})
                              (api/request (str "/api/articles") :POST
                                     (-> (om/get-state owner :editing-article)
                                         (assoc :article/curator user :article/thread (:db/id thread)))
                                     {:handler (fn [response]
                                                 (set! (.-href js/location) (str "#/article/" (:db/id response)))
                                                 (.reload js/location))
                                      :error-handler (fn [response xhrio]
                                                       (let [message
                                                             (condp == (.getStatus xhrio)
                                                               409 "Specified artifact name is already used."
                                                               (str response))]
                                                         (om/set-state! owner :error-map [[nil [message]]])))})))))}
           [:i.save.icon] "Save"]]
         (when-not (empty? error-map)
           [:div.ui.error.message
            (for [[_ messages] error-map]
              (for [message messages]
                [:div.header message]))])
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
                                 (om/update-state! owner [:editing-article :article/blocks]
                                                   (fn [blocks]
                                                     (assoc blocks
                                                            (dec index) (get blocks index)
                                                            index (get blocks (dec index)))))))}
                  [:i.caret.up.icon]]
                 [:button.ui.button
                  {:on-click (fn [_]
                               (when (< index (dec (count (om/get-state owner [:editing-article :article/blocks]))))
                                 (om/update-state! owner [:editing-article :article/blocks]
                                                   (fn [blocks]
                                                     (assoc blocks
                                                            (inc index) (get blocks index)
                                                            index (get blocks (inc index)))))))}
                  [:i.caret.down.icon]]
                 [:button.ui.button
                  {:on-click (fn [_]
                               (om/update-state! owner [:editing-article :article/blocks]
                                             (fn [blocks]
                                               (vec (concat (take index blocks)
                                                            (drop (inc index) blocks))))))}
                  [:i.close.icon]]]
                [:div.metadata
                 [:span (get-in curating-block [:curating-block/posted-by :user/name]) "(" (.format date-format-m (get-in curating-block [:curating-block/posted-at] (js/Date.))) ")"]]
                [:div.text
                 (if (= (:curating-block/id curating-block) 0)
                   (om/build editorial-space-view curating-block
                             {:state {:content (:curating-block/content curating-block)}
                              :opts {:save-fn (fn [content]
                                                (om/update-state! owner
                                                                  (fn [state]
                                                                    (-> state
                                                                        (update-in [:editing-article :article/blocks index]
                                                                                   assoc
                                                                                   :curating-block/content content
                                                                                   :curating-block/posted-at (js/Date.))
                                                                        (update-in [:error-map] dissoc :article/blocks)))))}})
                   (case (get-in curating-block [:curating-block/format :db/ident])
                     :curating-block.format/markdown {:dangerouslySetInnerHTML {:__html (.render js/md (:curating-block/content curating-block))}}
                     :curating-block.format/voice [:audio {:controls true
                                                           :src (str "/voice/" (:curating-block/content curating-block))}]
                     (:curating-block/content curating-block)))]]))
            (:article/blocks editing-article))]]]]]])))
