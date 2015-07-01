(ns back-channeling.layout
  (:use [environ.core :only [env]]
        [hiccup.page :only [html5 include-css include-js]]))

(defn layout [& body]
  (html5
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1, maximum-scale=1"}]
    (include-css "//cdnjs.cloudflare.com/ajax/libs/semantic-ui/1.12.3/semantic.min.css"
                 "//cdnjs.cloudflare.com/ajax/libs/highlight.js/8.6/styles/github.min.css"
                 "/css/back-channeling.css")
    (include-js "//cdnjs.cloudflare.com/ajax/libs/marked/0.3.2/marked.min.js"
                "//cdnjs.cloudflare.com/ajax/libs/highlight.js/8.6/highlight.min.js")
    (when (:dev env) (include-js "/react/react.js"))]
   [:body body]))

