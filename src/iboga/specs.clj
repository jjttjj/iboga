(ns iboga.specs
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [iboga.impl :as impl]
            [iboga.meta :as meta]
            [iboga.util :as u]
            [medley.core :as m]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;specs;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;for now we wont' be picky about numbers
(defn spec-for-class [class]
  (cond
    (#{Float/TYPE Double/TYPE Integer/TYPE Long/TYPE} class)
    number?

    (= Boolean/TYPE class)
    boolean?
    
    :else #(instance? class %)))

(defn ibkr-spec-key [k] (u/qualify-key k :ibkr))

(defn def-field-specs []
  (doseq [[k f] meta/spec-key->field]
    (let  [ibkr-key   (ibkr-spec-key k)
           collection (:java/collection f)
           class      (or collection (:java/class f))
           field-spec (or (meta/field-isa k) ibkr-key)]
      (eval `(s/def ~ibkr-key ~(spec-for-class class)))
      (eval `(s/def ~k ~(if collection
                          `(s/coll-of ~field-spec)
                          field-spec))))))

(defn def-enum-specs []
  (doseq [[k s] meta/enum-sets]
    (eval `(s/def ~k ~s))))

(defn def-struct-specs []
  (doseq [[k fields] meta/struct-key->field-keys]
    (let [fields (vec fields)]
      (eval `(s/def ~k (s/keys :opt-un ~fields))))))

(defn def-req-specs []
  (doseq [[req-key params] meta/req-key->field-keys]
    (eval
     `(s/def ~req-key (s/keys ~@(when (not-empty params) [:req-un params]))))))

(defn init-specs []
  (def-enum-specs)
  (def-struct-specs)
  (def-req-specs)
  (def-field-specs)
  (impl/def-included-specs))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;init;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(init-specs)
