(ns back-channeling.components.root
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [back-channeling.routing :as routing]
            [back-channeling.api :as api]
            [back-channeling.socket :as socket])
  (:use [back-channeling.components.board :only [board-view]]
        [cljs.reader :only [read-string]]))

(defn refresh-board [app board-name]
  (api/request (str "/api/board/" board-name)
               {:handler (fn [response]
                           (if (get-in app [:boards board-name])
                             (om/transact! app [:boards board-name :board/threads]
                                           (fn [threads]
                                             (merge-with merge (:board/threads response) threads)))
                             (let [board (update-in response [:board/threads]
                                                    (fn [threads]
                                                      (->> threads
                                                           (map (fn [t] {(:db/id t) t}))
                                                           (reduce merge {}))))]
                               (om/update! app [:boards board-name] board))))}))

(defn fetch-comments [app {:keys [board/name thread/id]}]
  (api/request (str "/api/thread/" id)
               {:handler (fn [thread]
                           (om/transact! app [:boards "default" :board/threads]
                                         (fn [threads]
                                           (assoc threads id thread))))}))

(defcomponent root-view [app owner]
  (will-mount [_]
    (routing/init app owner)
    (refresh-board app "default")
    (socket/open (str "ws://" (.-host js/location) "/ws")
                 :on-message (fn [message]
                               (let [[cmd data] (read-string message)]
                                 (case cmd
                                   :update-board (refresh-board app "default")
                                   :update-thread (fetch-comments app data))))))

  (render [_]
    (html
     (om/build board-view (get-in app [:boards "default"])))))
