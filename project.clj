(defproject net.unit8/back-channeling (clojure.string/trim-newline (slurp "VERSION"))
  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.clojure/data.json "0.2.6"]
                 [hiccup "1.0.5"]
                 [garden "1.2.5"]
                 [compojure "1.4.0"]
                 [environ "1.0.1"]
                 [buddy "0.6.2"]
                 
                 [org.clojure/clojurescript "1.7.58" :scope "provided"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [sablono "0.3.6"]
                 [prismatic/om-tools "0.3.12"]
                 [bouncer "0.3.3"]
                 [secretary "1.2.3"]
                 [org.omcljs/om "0.9.0"]
                 [io.undertow/undertow-websockets-jsr "1.1.1.Final"]

                 [com.datomic/datomic-free "0.9.5206" :exclusions [org.slf4j/slf4j-api org.slf4j/slf4j-nop joda-time
                                                                   com.amazonaws/aws-java-sdk]]
                 [ch.qos.logback/logback-classic "1.1.3"]
                 [datomic-schema "1.3.0"]
                 [liberator "0.13"]

                 [ring/ring-defaults "0.1.5" :exclusions [[javax.servlet/servlet-api]]]
                 [ring "1.4.0" :exclusions [ring/ring-jetty-adapter]]]

  :plugins [[lein-ring "0.9.3"]
            [lein-cljsbuild "1.0.6"]
            [lein-environ "1.0.0"]]
  :pom-plugins [[org.apache.maven.plugins/maven-assembly-plugin "2.5.5"
                 {:configuration [:descriptors [:descriptor "src/assembly/dist.xml"]]}]]
  
  :main back-channeling.core
  :aot :all

  :profiles {:dev {:env {:dev true}}}
  :cljsbuild {:builds
              [{:id "dev"
                :source-paths ["src/cljs"]
                :compiler {:output-to "resources/public/js/back-channeling.js"
                           :pretty-print true
                           :optimizations :simple}}
               {:id "production"
                :source-paths ["src/cljs"]
                :compiler {:output-to "resources/public/js/back-channeling.min.js"
                           :pretty-print false
                           :optimizations :advanced}}]})
