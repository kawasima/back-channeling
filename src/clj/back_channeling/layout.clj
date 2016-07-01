(ns back-channeling.layout
  (:use [environ.core :only [env]]
        [hiccup.page :only [html5 include-css include-js]]))

(defn layout [req & body]
  (html5
   [:head
    [:meta {:charset "utf-8"}]
    [:title "Back Channeling"]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1, maximum-scale=1"}]
    (when-let [user (:identity req)]
      (list
       [:meta {:property "bc:user:name" :content (:user/name user)}]
       [:meta {:property "bc:user:email" :content (:user/email user)}]))
    (include-css "//cdn.jsdelivr.net/semantic-ui/2.2.1/semantic.min.css"
                 "//cdn.jsdelivr.net/highlight.js/9.4.0/styles/github.min.css"
                 "/css/back-channeling.css")
    (include-js "//cdn.jsdelivr.net/markdown-it/7.0.0/markdown-it.min.js"
                "/js/vendors/markdown-it-emoji.min.js"
                "//twemoji.maxcdn.com/2/twemoji.min.js"
                "//cdn.jsdelivr.net/highlight.js/9.4.0/highlight.min.js"
                "//cdn.webrtc-experiment.com/MediaStreamRecorder.js"
                "//cdn.jsdelivr.net/zeroclipboard/2.2.0/ZeroClipboard.min.js")]
   [:body body]))
