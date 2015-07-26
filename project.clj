(defproject org.commos/service "0.2.0"
  :description "commos service protocol and cache"
  :url "http://github.com/commos/service"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 
                 #_[org.commos/shared "0.1.0"]
                 #_[org.commos/delta "0.2.3"]]
  :source-paths ["src/cljc"]
  :profiles {:dev {:dependencies [[org.clojure/clojurescript "0.0-3308"]]
                   :plugins [[lein-cljsbuild "1.0.6"]]
                   :test-paths ["test/cljc"]
                   :cljsbuild
                   {:builds [{:id "test"
                              :source-paths ["test/cljc"
                                             "test/cljs"]
                              :compiler {:output-to "target/js/test.js"
                                         :output-dir "target/js"
                                         :optimizations :none
                                         :target :nodejs
                                         :cache-analysis true}}]}}})
