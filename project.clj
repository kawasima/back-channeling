(defproject net.unit8/back-channeling (clojure.string/trim-newline (slurp "VERSION"))
  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :test-paths ["test/clj"]
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [duct/core "0.8.1"]
                 [duct/module.logging "0.5.0"]
                 [duct/module.web "0.7.4"]
                 [integrant "0.13.0"]
                 [duct/compiler.cljs "0.3.0"
                  :exclusions [duct/core integrant]]
                 [duct/server.figwheel "0.3.1"
                  :exclusions [duct/core integrant org.clojure/clojurescript http-kit]]
                 [http-kit "2.8.1"]
                 [org.clojure/data.json "2.5.1"]
                 [org.clojure/clojurescript "1.11.132" :scope "provided"]

                 [hiccup "2.0.0-RC3"]
                 [garden "1.3.10"]
                 [buddy/buddy-auth "3.0.323"]
                 [buddy/buddy-core "1.12.0-430"]
                 [buddy/buddy-sign "3.6.1-359"]
                 [buddy/buddy-hashers "2.0.167"]
                 [camel-snake-kebab "0.4.3"]

                 [org.clojure/core.async "1.7.701"]
                 [reagent "1.2.0"]
                 [cljsjs/react "18.2.0-1"]
                 [cljsjs/react-dom "18.2.0-1"]
                 [re-frame "1.4.3"]
                 [metosin/reitit-frontend "0.7.2"]
                 [metosin/malli "0.16.4"]
                 [io.undertow/undertow-websockets-jsr "2.3.23.Final"]
                 [org.jboss.threads/jboss-threads "3.9.2"]
                 [org.ring-clojure/ring-jakarta-servlet "1.15.3"]
                 [com.datomic/peer "1.0.7556"
                  :exclusions [org.slf4j/slf4j-api
                               org.slf4j/slf4j-nop
                               com.google.guava/guava]]
                 [com.google.guava/guava "33.4.0-jre"]
                 [cheshire "5.13.0"]
                 [com.fasterxml.jackson.core/jackson-core "2.17.0"]
                 [com.fasterxml.jackson.core/jackson-databind "2.17.0"]
                 [liberator "0.15.3"]
                 [clj-http "3.13.0"]]

  :plugins [[duct/lein-duct "0.12.3"]]
  :pom-plugins [[org.apache.maven.plugins/maven-assembly-plugin "2.5.5"
                 {:configuration [:descriptors [:descriptor "src/assembly/dist.xml"]]}]]

  :main ^:skip-aot back-channeling.main
  :target-path "target/%s"
  :uberjar-name "back-channeling-standalone.jar"
  :prep-tasks ["javac" "compile" ["run" ":duct/compiler"]]

  :profiles
  {:dev  [:project/dev  :profiles/dev]
   :test [:project/test :profiles/test]
   :repl {:prep-tasks ^:replace ["javac" "compile"]
          :repl-options {:init-ns user}}
   :uberjar {:aot :all}
   :profiles/dev   {}
   :profiles/test  {}
   :project/dev    {:dependencies [[integrant/repl "0.3.1"]
                                   [eftest "0.6.0"]
                                   [kerodon "0.8.0"]
                                   [binaryage/devtools "1.0.7"]]
                    :source-paths   ["dev/src"]
                    :resource-paths ["target/resources" "dev/resources" "resources"]}
   :project/test   {:prep-tasks ^:replace ["javac" "compile"]}})
