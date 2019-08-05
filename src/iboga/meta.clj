(ns iboga.meta
  (:require [clojure.tools.logging :as log]
            [medley.core :as m]
            [clojure.string :as str]
            [camel-snake-kebab.core :as csk]
            [clojure.java.io :as io])
  (:import [java.lang.reflect Modifier Method Parameter]
           [com.ib.client Types]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;names;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn remove-client-path [s] (str/replace s #"^com\.ib\.client\." ""))

(defn fix-case [s] (str/replace s "PnL" "Pnl"))

(defn remove-req [s] (str/replace s #"^(request|req(?=[A-Z]))" ""))

(defn default-rename-fn [s]
  (-> s fix-case remove-client-path remove-req csk/->kebab-case))

;;keyvec ct 1 = rename class
;;keyvec ct 2 = rename method
;;keyvec ct 3 = rename param
;;nil = wildcard
(def renames-map
  {["com.ib.client.EClient"]  "req"
   ["com.ib.client.EWrapper"] "recv"

   [nil "placeOrder" "id"] "order-id"
   
   [nil nil "reqId"]       "id"
   [nil nil "requestId"]   "id"
   [nil nil "tickerId"]    "id"

   ;;reqHistoricalData/Histogram/headTimestamp,etc
   [nil nil "useRth"]         "rth?"
   [nil nil "useRTH"]         "rth?"
   [nil nil "startDateTime"]  "start"
   [nil nil "endDateTime"]    "end"
   [nil nil "durationStr"]    "duration"
   [nil nil "whatToShow"]     "show"
   [nil nil "keepUpToDate"]   "update?"
   [nil nil "barSizeSetting"] "bar-size"

   [nil "updateMktDepthL2"] "update-mkt-depth-l2"

   [nil nil "endDateStr"]   "end"
   [nil nil "startDateStr"] "start"

   ["enum" nil "WhatToShow"]     "show"})

(defn rename
  ([class]
   (or (get renames-map [class])
       (default-rename-fn class)))
  ([class method]
   (or (get renames-map [class method])
       (get renames-map [nil method])
       (default-rename-fn method)))
  ([class method param]
   (or (get renames-map [class method param])
       (get renames-map [nil method param]) ;;defined method takes precedence over defined class
       (get renames-map [class nil param])
       (get renames-map [nil nil param])
       (default-rename-fn param))))

(defn ->spec-key
  "Takes an IB name string for some of class|method|param and returns
  a qualified spec-key"
  ([class] (->spec-key class nil nil))
  ([class method] (->spec-key class method nil))
  ([class method param]
   (let [segments (cond-> ["iboga"]
                    class  (conj (rename class))
                    method (conj (rename class method))
                    param  (conj (rename class method param)))]
     (keyword (str/join "." (butlast segments)) (last segments)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;java data;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def iboga-msg-classes
  #{com.ib.client.EWrapper
    com.ib.client.EClient})

(def iboga-struct-classes
  #{com.ib.client.Contract
    com.ib.client.ContractDetails
    com.ib.client.Order
    com.ib.client.ComboLeg
    com.ib.client.OrderState
    com.ib.client.TagValue
    com.ib.client.Bar})

(def iboga-enum-classes
  (conj (set (.getClasses Types))
        com.ib.client.OrderType))

(def handled-classes (into iboga-msg-classes iboga-struct-classes))

(def saved-names (read-string (slurp (io/resource "parameter-names.edn"))))

(defn param-name [^Parameter p]
  (get saved-names (hash p) (.getName p)))

(defn probably-getter? [^Method m]
  (boolean
   (and (= Modifier/PUBLIC (.getModifiers m))
        (zero? (.getParameterCount m))
        (not (#{"toString" "clone" "hashCode"} (.getName m))))))

(defn probably-setter? [^Method m]
  (boolean
   (and (= Modifier/PUBLIC (.getModifiers m))
        (= 1 (.getParameterCount m))
        (not (#{"equals"} (.getName m)))
        (= "void" (str (.getReturnType m))))))

(defn type-data [^Parameter p]
  (let [t  (.getType p)
        pt (.getParameterizedType p)]
    (cond
      ;;array -> vector not yet implemented
      #_
      (.isArray t)
      #_
      {:java/class     (.getComponentType t)
       :java/collection :array}
      
      (= t pt) 
      {:java/class t}

      :else
      (let [actual (.getActualTypeArguments pt)]
        (when-not (= 1 (count actual))
          (log/warn "Warning: paramter "
                    (.getName p)
                    " has more than one result for getActualtypeArguments:\n"
                    (mapv str actual)
                    "\nThis is not currently handled correctly. The raw type is: " t))
        
        {:java/class     (first actual)
         :java/collection t}))))

(defn param-data [cname mname param]
  (let [base (type-data param)
        isa  (when (iboga-struct-classes (:java/class base))
               (->spec-key (.getName (:java/class base))))]
    (cond-> (assoc base :spec-key (->spec-key cname mname (param-name param)))
      isa (assoc :isa isa))))

(defn method-data [^Method m]
  (let [cname  (.getName (.getDeclaringClass m))
        mname  (.getName m)
        params (.getParameters m)]
    {:params    (mapv (partial param-data cname mname) params)
     :ib-name   mname
     :spec-key (->spec-key cname mname)}))

;;currently, pick the non enum version of setters. TODO: pick enum setters
;;instead, if possible and spec them with :iboga/enum spec
(defn pick-setters [methods]
  (->> methods
       (group-by :spec-key)
       vals
       (mapcat
        (fn [grp]
          (cond->> grp
            (> (count grp) 1)
            (filter
             (fn [method]
               (let [p (first (:params method))]
                 (or (= (:java/class p) String)
                     (= (str (:java/class p)) "int"))))))))))

(defn struct-fields [clazz]
  (let [getters (->> clazz .getDeclaredMethods
                     (filter probably-getter?)
                     (map method-data))
        setters (->> clazz .getDeclaredMethods
                     (filter probably-setter?)
                     (map method-data)
                     pick-setters
                     )]
    (->> (concat getters setters)
         (group-by :spec-key)
         (map (fn [[spec-key methods]]
                (let [m1     (first methods)
                      setter (first (some (comp not-empty :params) methods))]
                  (-> setter ;;we use setter as the base because it contains param type info
                      (assoc
                       :spec-key spec-key
                       :ib-name   (:ib-name m1)
                       :setter?   (boolean setter)
                       :getter?   (boolean (some (comp empty? :params) methods))))))))))

(defn struct-data [clazz]
  {:spec-key        (->spec-key (.getName clazz))
   :java/class clazz
   :fields     (struct-fields clazz)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;enum;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;hack;improper use of ->spec-key
(def enum-sets
  (->> iboga-enum-classes
       (map (fn [c]
              (m/map-entry
               (->spec-key "enum" nil (str/replace (.getName c) #".*[$]" ""))
               (set (map str (.getEnumConstants c))))))
       (into {})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;msg;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def req-msgs
  (->> (.getDeclaredMethods com.ib.client.EClient)
       (filter #(re-find #"public synchronized void" (str %)))
       (map method-data)))

(def recv-msgs
  (->> (.getDeclaredMethods com.ib.client.EWrapper)
       (map method-data)))

;;note: the the method in EWrapper corresponding to :iboga.recv/error has
;;multiple signatures and thus that spec-key is not unique, and the lookup behavior
;;for it is undefined
(def msg-key->ib-name
  (->> (concat req-msgs recv-msgs)
       (m/index-by :spec-key)
       (m/map-vals :ib-name)))

(def req-key->fields
  (->> req-msgs
       (m/index-by :spec-key)
       (m/map-vals :params)))

(def req-key->field-keys
  (m/map-vals #(map :spec-key %) req-key->fields))

(def recv-key->fields
  (->> recv-msgs
       (m/index-by :spec-key)
       (m/map-vals :params)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;structs;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def structs (map struct-data iboga-struct-classes))

(def struct-field-key->ib-name
  (->> structs
       (mapcat :fields)
       (m/index-by :spec-key)
       (m/map-vals :ib-name)))

(def struct-key->class
  (->> structs
       (m/index-by :spec-key)
       (m/map-vals :java/class)))

(def struct-key->setter-fields
  (->> structs
       (m/index-by :spec-key)
       (m/map-vals :fields)
       (m/map-vals (fn [fields] (filter :setter? fields)))))

(def struct-class->getter-fields
  (->> structs
       (m/index-by :java/class)
       (m/map-vals :fields)
       (m/map-vals (fn [fields] (filter :getter? fields)))))

;;in most cases we only want setable fields
(def struct-key->field-keys
  (m/map-vals #(map :spec-key %) struct-key->setter-fields))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;fields;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;currently doesn't have recv messages
;;EWrapper has multiple arities for error so it cannot properly be inded
(def fields-for
  (merge struct-key->setter-fields
         req-key->fields))

(def spec-key->field
  (->> fields-for
       (mapcat val)
       (m/index-by :spec-key)))

(def field-isa
  (->> spec-key->field
       (m/filter-vals :isa)
       (m/map-vals :isa)))

(def field-collection
  (->> spec-key->field
       (m/filter-vals :java/collection)
       (m/map-vals :java/collection)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;ewrapper;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn param-tag [{:java/keys [collection class]}]
  (pr-str (or collection class)))

(defn param-tagged-sym [{:keys [spec-key] :as p}]
  (with-meta (symbol (name spec-key)) {:tag (param-tag p)}))

(defn params->map [params]
  (->> params
       (map (fn [{:keys [spec-key]}]
              [spec-key (symbol (name spec-key))]))
       (into {})))

(def ewrapper-data
  (map
   (fn [{:keys [params ib-name spec-key]}]
     {:signature (into ['this] (map param-tagged-sym params))
      :msym      (with-meta (symbol ib-name) {:tag "void"})
      :msg       [spec-key (params->map params)]})
   recv-msgs))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;repl helpers;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ib-msg-name->spec-key
  (m/map-kv
   (fn [k v]
     (m/map-entry (if (= (namespace k) "iboga.req")
                    (str "com.ib.client.EClient/" v)
                    (str "com.ib.client.EWrapper/" v))
                  k))
   msg-key->ib-name))
