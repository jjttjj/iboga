(ns iboga.client
  (:require [iboga.meta :as meta]
            [clojure.tools.logging :as log]
            [iboga.util :as u]
            [medley.core :as m]
            [iboga.impl :as impl]
            [iboga.specs]
            [clojure.spec.alpha :as s])
  (:import [com.ib.client EClientSocket EJavaSignal EReader]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;util;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn invoke [obj mname args]
  (clojure.lang.Reflector/invokeInstanceMethod obj mname (to-array args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;ib transformations;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn def-to-ib   [k f] (swap! impl/schema assoc-in [k :to-ib] f))
(defn def-from-ib [k f] (swap! impl/schema assoc-in [k :from-ib] f))

(defn get-to-ib   [k] (get-in @impl/schema [k :to-ib]))
(defn get-from-ib [k] (get-in @impl/schema [k :from-ib]))

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
      (invoke obj (meta/struct-field-key->ib-name k) [v]))
    obj))

(defn obj->map [obj]
  (->> (meta/struct-class->getter-fields (class obj))
       (map
        (fn [{:keys [spec-key ib-name]}]
          (when-some [v (invoke obj ib-name [])]
            (m/map-entry spec-key v))))
       (into {})))

(def to-java-coll
  {java.util.List (fn [xs] (java.util.ArrayList. xs))
   java.util.Map  (fn [xs] (java.util.HashMap xs))
   java.util.Set  (fn [xs] (java.util.HashSet. xs))})

(defn to-ib
  ([m]
   (m/map-kv #(m/map-entry %1 (to-ib %1 %2)) m))
  ([k x]
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
           type-key (map->obj (to-ib x) type-key)

           :else x))))))

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
;;EWrapper;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-reify-specs [cb]
  (map
   (fn [{:keys [msym signature msg]}]
     (list msym signature
           (list cb msg)))
   meta/ewrapper-data))

(defmacro wrapper [cb] 
  `(reify com.ib.client.EWrapper ~@(make-reify-specs cb)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;qualifying;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn qualify-map [parent m]
  (m/map-kv
   (fn [k v]
     (let [qk         (u/qualify-key parent k)
           field-type (meta/field-isa qk)
           ;;todo: this will cause an error before specs can be
           ;;checked if we expect a feild-type but receive a scalar
           v          (if field-type
                        (if (vector? v)
                          (mapv #(qualify-map field-type %) v)
                          (qualify-map field-type v))
                        v)]
       (m/map-entry qk v)))
   m))

(defn unqualify-walk [x]
  (cond
    (map? x)
    (m/map-kv
     (fn [k v]
       (m/map-entry (u/unqualify k)
                    (unqualify-walk v)))
     x)

    (sequential? x)
    (mapv unqualify-walk x)

    :else x))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;client;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn unqualify-msg [msg]
  (-> msg
      (update 0 u/unqualify)
      (update 1 (comp unqualify-walk from-ib))))

(defn process-messages [client reader signal]
  (while (.isConnected client)
    (.waitForSignal signal)
    (.processMsgs reader)))

;;should possibly return the client-id, as a promise?
(defn client
  "Takes one or more message handler functions and returns a map which
  represents an IB api client."
  [& handlers]
  (let [handlers       (atom (set handlers))
        handle-message (fn [msg]
                         (doseq [f @handlers]
                           (try (f (unqualify-msg msg))
                                (catch Throwable t
                                  (log/error t "Error handling message")))))
        wrap           (wrapper handle-message)
        sig            (EJavaSignal.)
        ecs            (EClientSocket. wrap sig)
        next-id        (atom 0)
        next-id-fn     #(swap! next-id inc)] ;;todo: seperate order ids?
    {:connect-fn (fn [host port & [client-id]]
                   (.eConnect ecs host port (or client-id (rand-int (Integer/MAX_VALUE))))
                   (let [reader (EReader. ecs sig)]
                     (.start reader)
                     (future (process-messages ecs reader sig))))
     :ecs        ecs
     :handlers   handlers
     :next-id    next-id-fn}))

(defn connect
  "Takes a connection map, a host string, a port number and optionally a
  client-id and connects to the IB api server. If no client id is
  provided, a random integer will be used."
  [conn host port & [client-id]]
  ((:connect-fn conn) host port client-id))

;;should disconnect remove hanlders?
(defn disconnect [conn] (-> conn :ecs .eDisconnect))

(defn connected? [conn] (-> conn :ecs .isConnected))

(defn add-handler [conn f] (swap! (:handlers conn) conj f))

(defn remove-handler [conn f] (swap! (:handlers conn) disj f))

;;TODO: next-id shouldn't clash with order-id. See:
;;https://github.com/InteractiveBrokers/tws-api/blob/master/source/javaclient/com/ib/controller/ApiController.java#L149
(defn next-id [conn] ((:next-id conn)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;req;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def req-params
  (->> meta/req-key->fields
       (map (fn [[k v]]
              [(u/unqualify k) (mapv (comp u/unqualify :spec-key) v)]))
       (into {})))

(defn req-spec-key [k & [arg]]
  (if arg
    (u/qualify-key (req-spec-key k) arg)
    (u/qualify-key :iboga/req k)))

(defn argmap->arglist [req-key arg-map]
  (mapv arg-map (meta/req-key->field-keys req-key)))

(def validate? (atom true))

(defn validate-reqs [b] (reset! validate? b))

(defn assert-valid-req [k arg-map]
  (when-not (s/valid? k arg-map)
    (throw (Exception. (ex-info "Invalid request" (s/explain-data k arg-map))))))

(defn maybe-validate [[req-key arg-map :as req-vec]]
  (when @validate?
    (assert-valid-req (req-spec-key req-key) arg-map)
    req-vec))

(defn req [conn [req-key arg-map :as req-vec]]
  (assert (connected? conn) "Not connected")
  (maybe-validate req-vec)
  (let [spec-key (req-spec-key req-key)
        ;;these two steps can/should be combined:
        qarg-map (qualify-map spec-key arg-map)
        ib-args  (to-ib qarg-map)]

    (invoke (:ecs conn)
            (meta/msg-key->ib-name spec-key)
            (argmap->arglist spec-key ib-args))
    req-vec))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;repl helpers;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn msg-name-search
  "Returns pairs of Interactive Brokers method name strings which contain the search string to the message key used to make requests/handle received messages in Iboga. Case insensitive."
  [ib-name-str]
  (->> meta/ib-msg-name->spec-key
       (m/map-vals u/unqualify)
       (filter #(.contains (.toLowerCase (first %)) ib-name-str))
       (sort-by first)))
