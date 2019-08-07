(ns iboga.meta.init
  (:require [iboga.meta :refer [method-data]]))

(defn hash-param [p]
  (hash (str (.getDeclaringExecutable p) (hash p))))

;;note: requires "-parameters" javac argument to get meaningful param names
(defn parameter-names [clazz]
  (->> (.getDeclaredMethods clazz)
       (mapcat
        #(for [p (.getParameters %)]
           [(hash-param p) (.getName p)]))
       (into {})))

#_(spit "resources/parameter-names.edn"
      (merge (parameter-names com.ib.client.EClient)
             (parameter-names com.ib.client.EWrapper)))
