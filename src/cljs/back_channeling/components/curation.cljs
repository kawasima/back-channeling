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

(defn open-thread [thread]
  (api/request (str "/api/thread/" (:db/id thread))
               {:handler (fn [response]
                           (om/update! thread [:thread/comments] (:thread/comments response)))}))

(defcomponent curation-page [thread owner]
  (init-state [_]
    {:selected-thread-comments #{}})
  (will-mount [_]
    (open-thread thread))
  (render-state [_ {:keys [selected-thread-comments]}]
    (html
     [:div.main.content
      [:div.ui.two.column.grid
      [:div.row
       [:div.column
        [:div.ui.thread.comments
         [:h3.ui.dividing.header (:thread/title thread)]
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
                          (:comment/content comment))]]])]]
       [:div.column]]]])))
