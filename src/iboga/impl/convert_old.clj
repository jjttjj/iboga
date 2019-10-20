(ns iboga.impl.convert-old
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [iboga.meta :as meta]
            [iboga.util :as u]
            [iboga.specs]
            [medley.core :as m])
  (:import [java.time LocalDate LocalDateTime]
           java.time.format.DateTimeFormatter))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;schema;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema (atom {}))

(defn def-to-ib   [k f] (swap! schema assoc-in [k :to-ib] f))
(defn def-from-ib [k f] (swap! schema assoc-in [k :from-ib] f))

(defn get-to-ib   [k] (get-in @schema [k :to-ib]))
(defn get-from-ib [k] (get-in @schema [k :from-ib]))

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
            to-ib-fn (or (get-to-ib type-key)
                         (get-to-ib k))]
        (cond
          ;;if we have a to-ib fn for its type or key we do that
          to-ib-fn (to-ib-fn x)
          
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
   (let [from-ib-fn (get-from-ib k)
         coll-type  (meta/field-collection k)]
     (cond
       coll-type
       (to-clj-coll coll-type (map #(from-ib (meta/field-isa k) %) x))
       
       ;;allow custom translation to/from ib
       (and (not from-ib-fn) (meta/struct-class->getter-fields (class x)))
       (from-ib (obj->map x))
       
       from-ib-fn (from-ib-fn x)
       
       (meta/iboga-enum-classes (type x)) (str x)
       
       :else x))))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;spec helpers;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn local-date-time? [x]
  (instance? LocalDateTime x))

(defn date? [x]
  (instance? LocalDate x))

(s/def ::rth? boolean?)

(def rth? {:spec ::rth? :to-ib bool->bit})

(def format-date {:spec #{1}})

(def recv-date-str {:from-ib parse-ib-time})

(def contract-interval-formatter (DateTimeFormatter/ofPattern "yyyyMMdd[:HHmm]"))

;;the time zone of open/close is the contract's :time-zone-id in contract-dteails
;;https://interactivebrokers.github.io/tws-api/classIBApi_1_1ContractDetails.html#aa4434636ba3f3356fb804d9cf257f452
(defn parse-contract-hours [hours-str]
  (->> (str/split hours-str #";")
       (keep
        (fn [s]
          (if-not (.contains s "CLOSED")
            (->> (str/split s #"-")
                 (mapv #(LocalDateTime/parse % contract-interval-formatter))
                 (zipmap [:open :close]))
            {:closed (-> s
                         (str/replace #":CLOSED$" "")
                         (LocalDate/parse contract-interval-formatter))})))))

(def default-schema
  (merge

   #:iboga
   {:tag-value {:spec (s/keys :req-un [:iboga.tag-value/tag
                                       :iboga.tag-value/value])

                ;;TagValues use public fields and are thus not handled by the
                ;;getter/setter mechanisms of iboga
                
                :to-ib #(com.ib.client.TagValue.
                         (str (:iboga.tag-value/tag %))
                         (str (:iboga.tag-value/value %)))

                :from-ib #(hash-map :iboga.tag-value/tag (.-m_tag %)
                                    :iboga.tag-value/value (.-m_value %))}}

   #:iboga.order
   
   {:total-quantity {:spec pos? ;;ib uses float
                     }}
   
   #:iboga.bar
   {:time {:from-ib parse-ib-time}}

   #:iboga.tag-value{:tag   {:spec any?}
                     :value {:spec any?}}

   #:iboga.contract
   {:sec-type                          {:spec :iboga.enum/sec-type}
    :last-trade-date-or-contract-month {:spec    date?
                                        :to-ib   format-ib-time
                                        :from-ib parse-ib-time}}
   #:iboga.req.historical-data
   {:end         {:spec  (s/nilable local-date-time?)
                  :to-ib #(when % (format-ib-time %))}
    :duration    {:spec #(re-find #"[0-9]+ [SDWMY]$" %)}
    :bar-size    {:spec :iboga.enum/bar-size}
    :show        {:spec :iboga.enum/show}
    :format-date format-date
    :rth?        rth?}
   
   #:iboga.req.head-timestamp
   {:show        {:spec :iboga.enum/show}
    :format-date format-date
    :rth?        rth?}

   #:iboga.recv.historical-data-end
   {:start recv-date-str
    :end   recv-date-str}

   #:iboga.recv.head-timestamp
   {:head-timestamp recv-date-str}

   #:iboga.recv.current-time
   {:time parse-ib-time}
   

   #:iboga.contract-details
   {:liquid-hours    {:from-ib parse-contract-hours}
    :trading-hours   {:from-ib parse-contract-hours}
    :valid-exchanges {:from-ib #(set (str/split % #","))}
    :order-types     {:from-ib #(set (str/split % #","))}}))


(defn def-included-specs []
  (doseq [[k {:keys [spec]}] default-schema]
    (when spec
      (eval `(s/def ~k ~spec)))))



(defn init []
  (reset! schema default-schema)
  (def-included-specs))

(init)
