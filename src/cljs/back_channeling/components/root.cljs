(ns back-channeling.components.root
  (:require [om.core :as om :include-macros true]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan timeout]]
            [back-channeling.routing :as routing]
            [back-channeling.api :as api]
            [back-channeling.socket :as socket]
            [back-channeling.notification :as notification]
            [back-channeling.components.avatar :refer [avatar]])
  (:use [back-channeling.components.board :only [board-view boards-view]]
        [back-channeling.components.curation :only [article-page]]
        [back-channeling.component-helper :only [make-click-outside-fn]]
        [cljs.reader :only [read-string]]))

(defn refresh-boards [app]
  (api/request "/api/boards"
               {:handler (fn [response]
                           (let [boards (->> response
                                             (map #(assoc % :board/threads {}))
                                             (reduce #(assoc %1 (:board/name %2) {:value %2}) {}))]
                             (om/update! app :boards boards)))}))

(defn refresh-board [app board-name]
  (api/request (str "/api/board/" board-name)
               {:handler (fn [response]
                           (om/update! app [:board] response))}))

(defn fetch-comments
  ([app thread]
   (fetch-comments app thread 1 nil))
  ([app thread from]
   (fetch-comments app thread from nil))
  ([app {:keys [board/name db/id]} from to]
   (api/request
    (str "/api/board/" name "/thread/" id "/comments/" from "-" to)
    {:handler (fn [fetched-comments]
                (om/transact!
                 app [:thread id :thread/comments]
                 #(concat % fetched-comments)))})))

(defn find-thread [threads id]
  (->> (map-indexed vector threads)
       (filter #(= (:db/id (second %)) id))
       (map first)
       first))

(defn refresh-thread [app thread]
  (when (= (:board/name thread) (get-in @app [:board :board/name]))
    (om/transact! app [:board :board/threads]
                  #(update-in % [(find-thread % (:db/id thread))]
                              assoc
                              :thread/last-updated (:thread/last-updated thread)
                              :thread/resnum (:thread/resnum thread)))
    (when (get-in @app [:threads (:db/id thread) :thread/active?])
      (fetch-comments app thread
                      (or (:comments/from thread)
                          (inc (count
                                (or (get-in @app [:threads (:db/id thread) :thread/comments]) 0))))
                      (:comments/to thread)))))

(defn search-threads [owner board-name query]
  (api/request (str "/api/board/" board-name "/threads?q=" (js/encodeURIComponent query))
               {:handler (fn [results]
                           (om/set-state! owner :search-result results))}))

(defn connect-socket [app token]
  (socket/open (str (if (= "https:" (.-protocol js/location)) "wss://" "ws://")
                    (.-host js/location)
                    (some-> js/document
                            (.querySelector "meta[property='bc:prefix']")
                            (.getAttribute "content"))
                    "/ws?token=" token)
                 :on-message (fn [message]
                               (let [[cmd data] (read-string message)]
                                 (case cmd
                                   :notify (notification/show data)
                                   :update-board (refresh-board app (:board/name data))
                                   :update-thread (refresh-thread app data)
                                   :join  (om/transact! app [:users] #(conj % data))
                                   :leave (om/transact! app [:users] #(disj % data))
                                   :call  (js/alert (:message data)))))))

(defn root-view [app owner]
  (reify
    om/IInitState
    (init-state [_]
      {:open-profile? false
       :open-users? false
       :search-result nil
       :board-channel (chan)
       :user {:user/name (.. js/document (querySelector "meta[property='bc:user:name']") (getAttribute "content"))
              :user/email (.. js/document (querySelector "meta[property='bc:user:email']") (getAttribute "content"))}
       :called-message nil
       :click-outside-fn nil})

    om/IWillMount
    (will-mount [_]
      (routing/init app owner)
      (api/request "/api/reactions"
                   {:handler
                    (fn [response]
                      (om/update! app :reactions response))})

      (api/request "/api/users"
                   {:handler
                    (fn [response]
                      (om/update! app :users (apply hash-set response)))})

      (api/request "/api/token" :POST
                   {:handler
                    (fn [response]
                      (connect-socket app (:access-token response)))
                    :error-handler
                    (fn [response error-code]
                      (set! (.. js/document -location -href) "/"))})

      (when-let [on-click-outside (om/get-state owner :click-outside-fn)]
        (.removeEventListener js/document "mousedown" on-click-outside)))

    om/IDidMount
    (did-mount [_]
      (when-not (om/get-state owner :click-outside-fn)
        (om/set-state! owner :click-outside-fn
                       (make-click-outside-fn
                        (.. (om/get-node owner) (querySelector "div.site.menu"))
                        (fn [_]
                          (om/update-state! owner #(assoc %
                                                          :open-profile? false
                                                          :open-users? false
                                                          :search-result nil))))))
      (.addEventListener js/document "mousedown"
                         (om/get-state owner :click-outside-fn)))

    om/IRenderState
    (render-state [_ {:keys [open-profile? open-users? search-result user board-channel]}]
      (html
       [:div.full.height
        [:div.ui.fixed.site.menu
         [:div.item
          [:a {:href "#/"}
           [:img.ui.logo.image {:src (str (om/get-shared owner :prefix) "/img/logo.png")
                                :alt "Back Channeling"}]]]
         [:div.center.menu
          (when (= (:page app) :board)
            [:a.item {:href "#/"}
             [:h2.ui.header [:i.block.layout.icon] [:div.content (:target-board-name app)]]])
          [:div.item
           [:div.ui.search
            [:div.ui.icon.input
             [:input#search.prompt
              {:type "text"
               :placeholder "Keyword"
               :on-key-up (fn [_]
                            (if-let [query (.. js/document (getElementById "search") -value)]
                              (if (> (count query) 2)
                                (search-threads owner (get-in app [:board :board/name]) query)
                                (om/set-state! owner :search-result nil))))}]
             [:i.search.icon]]
            (when (not-empty search-result)
              [:div.results.transition.visible
               (for [res search-result]
                 [:a.result {:on-click
                             (fn [_]
                               (om/set-state! owner :search-result nil)
                               (set! (.-href js/location) (str "#/board/" (:board/name res) "/" (:db/id res))))}
                  [:div.content
                   [:div.title (:thread/title res)]]])])]]]

         [:div.right.menu
          [:a.item
           [:div {:on-click (fn [_]
                              (om/set-state! owner :open-users? (not open-users?)))}
            [:i.users.icon]
            [:div.ui.label (count (:users app))]]
           (when open-users?
             [:div.ui.flowing.popup.right.bottom.transition.visible {:style {:top "60px" :width "200px"}}
              [:div.ui.four.column.grid
               (for [member (:users app)]
                 [:column {:on-click (fn [_]
                                       (socket/send :call {:from user
                                                           :to #{member}
                                                           :message (str (:user/name user) " is calling!!")}))}
                  (om/build avatar member)])]])]
          [:div.ui.dropdown.item
           [:div {:on-click (fn [_]
                              (om/set-state! owner :open-profile? (not open-profile?)))}
            (om/build avatar user)
            [:span (:user/name user)] ]
           [:div.menu.transition {:class (if open-profile? "visible" "hidden")}
            [:a.item {:href "/logout"} "Logout"]]]]]
        (case (:page app)
          :boards (om/build boards-view (:boards app))
          :board (om/build board-view (:threads app)
                           {:init-state {:channel board-channel}
                            :opts {:user user
                                   :board (:board app)
                                   :reactions (:reactions app)}})
          :article (om/build article-page (:article app)
                             {:init-state {:thread (->> (:threads app)
                                                        (filter #(= (:thread/active? %) true))
                                                        first)}
                              :opts {:user user
                                     :board-name (get-in app [:board :board/name])}}))]))))
