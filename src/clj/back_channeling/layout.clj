(ns back-channeling.layout
  (:require [hiccup.page :refer [html5 include-css include-js]]))

(defn layout [prefix req & body]
  (html5
   [:head
    [:meta {:charset "utf-8"}]
    [:title "Back Channeling"]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
    [:link {:rel "icon" :href (str prefix "/favicon.ico")}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1, maximum-scale=1"}]
    [:meta {:property "bc:prefix" :content prefix}]
    (when-let [user (:identity req)]
      (list
       [:meta {:property "bc:user:name" :content (:user/name user)}]
       [:meta {:property "bc:user:email" :content (:user/email user)}]))
    (include-css "//cdn.jsdelivr.net/npm/fomantic-ui@2.9.3/dist/semantic.min.css"
                 "//cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/styles/github.min.css"
                 (str prefix "/css/back-channeling.css"))
    (include-js "//cdn.jsdelivr.net/npm/markdown-it@14.1.0/dist/markdown-it.min.js"
                (str prefix "/js/vendors/markdown-it-emoji.min.js")
                "//cdn.jsdelivr.net/npm/twemoji@14.0.2/dist/twemoji.min.js"
                "//cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/highlight.min.js")]
   [:body body]))
