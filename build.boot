(set-env!
 :dependencies '[[adzerk/bootlaces "0.1.13" :scope "test"]
                 [metosin/bat-test "0.4.2" :scope "test"]
                 [org.clojure/tools.nrepl "0.2.13" :scope "test"]
                 [samestep/boot-refresh "0.1.0" :scope "test"]
                 [org.clojure/clojure "1.10.0" :scope "test"]
                 [org.slf4j/slf4j-simple "1.7.25" :scope "test"]

                 [cheshire "5.8.1"]
                 [com.fzakaria/slf4j-timbre "0.3.12"]
                 [com.taoensso/timbre "4.10.0"]
                 [io.logz.sender/logzio-sender "1.0.18" :exclusions [org.sl4j/slf4j-api]]
                 [org.slf4j/jcl-over-slf4j "1.7.25"]
                 [org.slf4j/jul-to-slf4j "1.7.25"]
                 [org.slf4j/log4j-over-slf4j "1.7.25"]
                 [org.slf4j/slf4j-api "1.7.25"]]
 :source-paths #{"src/clj"})

(run! #(ns-unmap *ns* %) '[test])

(require '[adzerk.bootlaces :as bootlaces]
         '[clojure.tools.namespace.repl :refer [set-refresh-dirs]]
         '[metosin.bat-test :refer [bat-test]]
         '[samestep.boot-refresh :refer [refresh]])

(def +version+ "0.1.0-SNAPSHOT")

(task-options!
 aot {:all true}
 jar {:file "bjorr.jar"}
 pom {:project 'com.ninjakoala/bjorr
      :version +version+
      :description "Timbre appender to send to Logz.io"
      :url "https://github.com/ninjakoala/bjorr"
      :scm {:url "https://github.com/ninjakoala/bjorr"}
      :license {"Eclipse Public License" "http://www.eclipse.org/legal/epl-v10.html"}}
 push {:ensure-branch "master"
       :ensure-clean true
       :gpg-sign true
       :repo-map {:url "https://clojars.org/repo"}}
 repl {:server true}
 watch {:verbose true})

(deftask testing
  []
  (set-env! :source-paths #(conj % "test/clj"))
  (apply set-refresh-dirs (get-env :directories))
  identity)

(deftask development
  []
  (testing))

(deftask test
  []
  (comp
   (testing)
   (bat-test)))

(deftask dev
  []
  (comp
   (development)
   (repl)
   (watch)
   (refresh)
   (target)))

(deftask build
  []
  (comp
   (aot)
   (pom)
   (jar)
   (install)
   (#'bootlaces/update-readme-dependency)))

(deftask snapshot
  []
  (comp
   (build)
   (push :ensure-snapshot true)))

(deftask release
  []
  (comp
   (build)
   (push :ensure-release true
         :tag true)))
