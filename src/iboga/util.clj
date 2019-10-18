(ns iboga.util)

(defn unqualify [k] (keyword (name k)))

(defn qualify-key [parent k]
  (keyword (str (namespace parent) "." (name parent)) (name k)))
