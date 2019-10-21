(ns iboga.impl.convert
  (:require [clojure.string :as str]
            [iboga.meta :as meta]
            [iboga.util :as u]
            [medley.core :as m])
  (:import [java.time LocalDate LocalDateTime]
           java.time.format.DateTimeFormatter))

(defmulti to-ib* (fn [k v] k))
(defmulti from-ib* (fn [k v] k))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;convert;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ib-datetime-formatter (DateTimeFormatter/ofPattern "yyyyMMdd[  HH:mm:ss]"))

(defn format-ib-time [t] (.format ib-datetime-formatter t))

(defn parse-ib-time [x]
  (if (= (count x) 8) ;;breaks in year 10000
    (-> x (LocalDate/parse ib-datetime-formatter))
    (-> x (LocalDateTime/parse ib-datetime-formatter))))

(defn bool->bit [bool] (case bool true 1 false 0))

(def contract-interval-formatter (DateTimeFormatter/ofPattern "yyyyMMdd[:HHmm]"))

;;the time zone of open/close is the contract's :time-zone-id in contract-dteails
;;https://interactivebrokers.github.io/tws-api/classIBApi_1_1ContractDetails.html#aa4434636ba3f3356fb804d9cf257f452
(defn parse-contract-hours [hours-str]
  (->> (str/split hours-str #";")
       (keep
        (fn [s]
          (when (not= s "")
            (if-not (.contains s "CLOSED")
              (->> (str/split s #"-")
                   (mapv #(LocalDateTime/parse % contract-interval-formatter))
                   (zipmap [:open :close]))
              {:closed (-> s
                           (str/replace #":CLOSED$" "")
                           (LocalDate/parse contract-interval-formatter))}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;methods;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;TagValues use public fields and are thus not handled by the
;;getter/setter mechanisms of iboga
(defmethod to-ib* :iboga/tag-value [k v]
  (com.ib.client.TagValue.
   (str (:iboga.tag-value/tag v))
   (str (:iboga.tag-value/value v))))

(defmethod from-ib* :iboga/tag-value [k v]
  (hash-map :iboga.tag-value/tag (.-m_tag v)
            :iboga.tag-value/value (.-m_value v)))

(defmethod from-ib* :iboga.bar/time [k x] (parse-ib-time x))

(defmethod to-ib* :iboga.contract/last-trade-date-or-contract-month [k x]
  (format-ib-time x))

(defmethod from-ib* :iboga.contract/last-trade-date-or-contract-month [k x]
  (parse-ib-time x))

(defmethod to-ib* :iboga.req.historical-data/end [k v]
  (when v (format-ib-time v)))

(defmethod to-ib* :iboga.req.historical-data/rth? [k x]
  (bool->bit x))

(defmethod to-ib* :iboga.req.head-timestamp/rth? [k x]
  (bool->bit x))

(defmethod from-ib* :iboga.recv.historical-data-end/start [k x]
  (parse-ib-time x))

(defmethod from-ib* :iboga.recv.historical-data-end/end [k x]
  (parse-ib-time x))

(defmethod from-ib* :iboga.recv.head-timestamp/head-timestamp [k x]
  (parse-ib-time x))

(defmethod from-ib* :iboga.recv.current-time/time [k x]
  (parse-ib-time x))

(defmethod from-ib* :iboga.contract-details/liquid-hours [k x]
  (parse-contract-hours x))
(defmethod from-ib* :iboga.contract-details/trading-hours [k x]
  (parse-contract-hours x))
(defmethod from-ib* :iboga.contract-details/valid-exchanges [k x]
  (set (str/split x #",")))
(defmethod from-ib* :iboga.contract-details/order-types [k x]
  (set (str/split x #",")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;transform;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn construct [struct-key args]
  (let [cname (name (.getName (meta/struct-key->class struct-key)))]
    (clojure.lang.Reflector/invokeConstructor
     (resolve (symbol cname))
     (to-array args))))

(defn map->obj [m type-key]
  (let [obj (construct type-key [])]
    (doseq [[k v] m]
      (u/invoke obj (meta/struct-field-key->ib-name k) [v]))
    obj))

(defn obj->map [obj]
  (->> (meta/struct-class->getter-fields (class obj))
       (map
        (fn [{:keys [spec-key ib-name]}]
          (when-some [v (u/invoke obj ib-name [])]
            (m/map-entry spec-key v))))
       (into {})))

(def to-java-coll
  {java.util.List (fn [xs] (java.util.ArrayList. xs))
   java.util.Map  (fn [xs] (java.util.HashMap xs))
   java.util.Set  (fn [xs] (java.util.HashSet. xs))})

(defn to-ib
  [k x]
  (let [collection-class (meta/field-collection k)]
    (if-let [java-coll-fn (to-java-coll collection-class)]
      (java-coll-fn (map #(to-ib (meta/field-isa k) %) x))
      
      (let [type-key (or
                      ((set (keys meta/struct-key->class)) k)
                      (meta/field-isa k))
            to-ib-fn (or (get-method to-ib* type-key)
                         (get-method to-ib* k))]
        (cond
          ;;if we have a to-ib fn for its type or key we do that
          to-ib-fn (to-ib-fn ::_ x)

          ;;if it has a type but no custom translation, we turn it into the type of
          ;;object described by its type key
          type-key (map->obj x type-key)

          :else x)))))

(defn to-clj-coll [coll-type xs]
  (condp isa? coll-type
    java.util.ArrayList (into [] xs)
    java.util.HashMap   (into {} xs)
    java.util.HashSet   (into #{} xs)
    ;;else it should be an array
    (vec xs)))

(defn from-ib
  ([m] (m/map-kv #(m/map-entry %1 (from-ib %1 %2)) m))
  ([k x]
   (let [from-ib-fn (get-method from-ib* k)
         coll-type  (meta/field-collection k)]
     (cond
       coll-type
       (to-clj-coll coll-type (map #(from-ib (meta/field-isa k) %) x))
       
       ;;allow custom translation to/from ib
       (and (not from-ib-fn) (meta/struct-class->getter-fields (class x)))
       (from-ib (obj->map x))

       from-ib-fn (from-ib-fn k x)

       (meta/iboga-enum-classes (type x)) (str x)
       
       :else x))))
