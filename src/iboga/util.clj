(ns iboga.util)

(defn unqualify [k] (keyword (name k)))

(defn qualify-key [parent k]
  (keyword (str (namespace parent) "." (name parent)) (name k)))

(defn invoke [obj mname args]
  (clojure.lang.Reflector/invokeInstanceMethod obj mname (to-array args)))
