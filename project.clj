(defproject iboga "0.2.0-SNAPSHOT"
  :description "A minimum viable Interactive Brokers api client"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/core.async "0.4.490"]]
  :profiles {:dev {:dependencies [[com.interactivebrokers/tws-api "9.73.01-SNAPSHOT"]]
                   :source-paths ["dev"]}})
