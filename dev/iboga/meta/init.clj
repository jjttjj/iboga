(ns iboga.meta.init
  (:require [iboga.meta :refer [method-data]]))

;;note: requires "-parameters" javac argument to get meaningful param names
(defn parameter-names [clazz]
  (->> (.getDeclaredMethods clazz)
       (mapcat
        #(for [p (.getParameters %)]
           [(hash p) (.getName p)]))
       (into {})))

(spit "resources/parameter-names.edn"
      (merge (parameter-names com.ib.client.EClient)
             (parameter-names com.ib.client.EWrapper)))
