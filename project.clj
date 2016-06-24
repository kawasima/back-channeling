(defproject net.unit8/back-channeling (clojure.string/trim-newline (slurp "VERSION"))
  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [com.stuartsierra/component "0.3.1"]
                 [duct "0.7.0"]
                 [org.clojure/data.json "0.2.6"]

                 [meta-merge "1.0.0"]
                 [hiccup "1.0.5"]
                 [garden "1.3.2"]
                 [compojure "1.5.1"]
                 [environ "1.0.3"]
                 [buddy "1.0.0"]

                 [org.clojure/clojurescript "1.9.76" :scope "provided"]
                 [org.clojure/core.async "0.2.374"]
                 [sablono "0.7.2"]
                 [prismatic/om-tools "0.4.0"]
                 [bouncer "1.0.0"]
                 [secretary "1.2.3"]
                 [org.omcljs/om "1.0.0-alpha36"]
                 [io.undertow/undertow-websockets-jsr "1.1.1.Final"]
                 [com.google.guava/guava "19.0"]
                 [com.datomic/datomic-free "0.9.5359"
                  :exclusions [org.slf4j/slf4j-api
                               org.slf4j/slf4j-nop
                               joda-time
                               com.amazonaws/aws-java-sdk
                               com.google.guava/guava]]
                 [ch.qos.logback/logback-classic "1.1.7"]
                 [datomic-schema "1.3.0"]
                 [liberator "0.14.1"]

                 [ring/ring-defaults "0.2.0" :exclusions [[javax.servlet/servlet-api]]]
                 [ring "1.4.0" :exclusions [ring/ring-jetty-adapter]]]

  :plugins [[lein-cljsbuild "1.1.3"]
            [lein-environ "1.0.3"]]
  :pom-plugins [[org.apache.maven.plugins/maven-assembly-plugin "2.5.5"
                 {:configuration [:descriptors [:descriptor "src/assembly/dist.xml"]]}]]

  :main ^:skip-aot back-channeling.main
  :target-path "target/%s"
  :resource-paths ["resources" "target/cljsbuild"]
  :prep-tasks [["javac"] ["cljsbuild" "once"] ["compile"]]
  :aliases {"run-task" ["with-profile" "+repl" "run" "-m"]
            "setup"    ["run-task" "dev.tasks/setup"]}

  :cljsbuild
  {:builds
   [{:id "dev"
     :source-paths ["src/cljs"]
     :compiler {:output-to "target/cljsbuild/back-channeling/public/js/back-channeling.js"
                :pretty-print true
                :optimizations :simple}}
    {:id "production"
     :source-paths ["src/cljs"]
     :compiler {:output-to "resources/public/js/back-channeling.min.js"
                :pretty-print false
                :optimizations :advanced}}]}

  :profiles
  {:dev  [:project/dev  :profiles/dev]
   :test [:project/test :profiles/test]
   :repl {:resource-paths ^:replace ["resources" "target/figwheel"]
          :prep-tasks     ^:replace [["javac"] ["compile"]]}
   :uberjar {:aot :all}
   :profiles/dev   {}
   :profiles/test  {}
   :project/dev    {:dependencies [[duct/generate "0.7.0"]
                                   [reloaded.repl "0.2.2"]
                                   [org.clojure/tools.namespace "0.2.11"]
                                   [org.clojure/tools.nrepl "0.2.12"]
                                   [eftest "0.1.1"]
                                   [com.gearswithingears/shrubbery "0.3.1"]
                                   [kerodon "0.7.0"]
                                   [binaryage/devtools "0.6.1"]
                                   [com.cemerick/piggieback "0.2.1"]
                                   [duct/figwheel-component "0.3.2"]
                                   [figwheel "0.5.0-6"]]
                    :source-paths ["dev"]
                    :repl-options {:init-ns user
                                   :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
                    :env {:port "3009" :dev true}}
   :project/test   {}})
