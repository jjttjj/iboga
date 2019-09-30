(ns iboga.meta.init
  (:require [iboga.meta :refer [hash-param method-data]]))

;;note: requires "-parameters" javac argument to get meaningful param names
(defn parameter-names [clazz]
  (->> (.getDeclaredMethods clazz)
       (mapcat
        #(map-indexed (fn [ix p] [(hash-param p ix) (.getName p)]) (.getParameters %))
        #_#(for [p (.getParameters %)]
           [(hash-param p) (.getName p)]))
       (into {})))

#_(spit "resources/parameter-names.edn"
      (merge (parameter-names com.ib.client.EClient)
             (parameter-names com.ib.client.EWrapper)))
