(ns iboga.specs
  (:require [clojure.spec.alpha :as s]
            [iboga.meta :as meta]
            [iboga.util :as u])
  (:import [java.time LocalDate LocalDateTime]))

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
  (def-field-specs))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;init;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(init-specs)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;custom specs;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn local-date-time? [x]
  (instance? LocalDateTime x))

(defn date? [x]
  (instance? LocalDate x))

(s/def :iboga/tag-value
  (s/keys :req-un [:iboga.tag-value/tag :iboga.tag-value/value]))

(s/def :iboga.contract/sec-type :iboga.enum/sec-type)
(s/def :iboga.contract/last-trade-date-or-contract-month date?)


(s/def ::rth? boolean?)

(s/def :iboga.req.historical-data/end (s/nilable local-date-time?))
(s/def :iboga.req.historical-data/duration    #(re-find #"[0-9]+ [SDWMY]$" %))
(s/def :iboga.req.historical-data/bar-size    :iboga.enum/bar-size)
(s/def :iboga.req.historical-data/show        :iboga.enum/show)
(s/def :iboga.req.historical-data/format-date #{1})
(s/def :iboga.req.historical-data/rth?        ::rth?)

(s/def :iboga.req.head-timestamp/show        :iboga.enum/show)
(s/def :iboga.req.head-timestamp/format-date #{1})
(s/def :iboga.req.head-timestamp/rth?        ::rth?)

