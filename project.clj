(defproject net.unit8/back-channeling (clojure.string/trim-newline (slurp "VERSION"))
  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :test-paths ["test/clj"]
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.9.0-alpha19"]
                 [duct/core "0.6.1"]
                 [duct/module.logging "0.3.1"]
                 [duct/module.web "0.6.2"]
                 [duct/module.cljs "0.3.1"]
                 [org.clojure/data.json "0.2.6"]

                 [hiccup "1.0.5"]
                 [garden "1.3.2"]
                 [buddy "2.0.0"]
                 [camel-snake-kebab "0.4.0"]

                 [org.clojure/core.async "0.3.443"]
                 [sablono "0.8.0"]
                 [bouncer "1.0.0"]
                 [secretary "1.2.3"]
                 [org.omcljs/om "1.0.0-beta1"]
                 [io.undertow/undertow-websockets-jsr "1.4.20.Final"]
                 ;;[com.google.guava/guava "19.0"]
                 [com.datomic/datomic-free "0.9.5385"
                  :exclusions [org.slf4j/slf4j-api
                               org.slf4j/slf4j-nop
                               joda-time
                               com.amazonaws/aws-java-sdk
                               com.google.guava/guava]]
                 [liberator "0.15.1"]]

  :plugins [[duct/lein-duct "0.10.2"]]
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
   :project/dev    {:dependencies [[integrant/repl "0.2.0"]
                                   [eftest "0.3.1"]
                                   [kerodon "0.8.0"]]
                    :source-paths   ["dev/src"]
                    :resource-paths ["resources" "dev/resources" "target/resources"]}
   :project/test   {}})
