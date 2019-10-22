(ns iboga.client
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [iboga.impl.convert :as convert]
            [iboga.meta :as meta]
            [iboga.util :as u]
            [iboga.specs]
            [medley.core :as m])
  (:import [com.ib.client EClientSocket EJavaSignal EReader]))

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

(defn walk-qualified
  "Walks the structure x with spec key parent. Recursively qualifies all
  keywords in maps and sequences of maps with the spec key their
  corresponding TWS api type, then calls a two-argument function f on
  the qualified keyword and value, returning the value in place."
  ([f parent x] (walk-qualified f parent x identity))
  ([f parent x after]
   (walk/walk
    (fn [x]
      (if (map-entry? x)
        (let [k          (key x)
              v          (val x)
              qkey       (u/qualify-key parent k)
              field-type (meta/field-isa qkey)
              type       (or field-type qkey)]
          (m/map-entry
           qkey
           ;;give then entry a key qualified with it's parent,
           ;;but then process the value according to it's "type".
           ;;finally, callback f on the result with the parent-key
           (walk-qualified f type v (fn [x] (f qkey x)))))
        (walk-qualified f parent x)))
    after
    x)))

;;todo: this + from-ib should be more similar to walk-qualified+to-ib:
;;combining the walking and modification in one step
(defn deep-unqualify
  "Recursivly unqualifies all keys in maps or sequences of maps"
  [x]
  (cond
    (map? x)
    (m/map-kv
     (fn [k v]
       (m/map-entry (u/unqualify k)
                    (deep-unqualify v)))
     x)

    (sequential? x)
    (mapv deep-unqualify x)

    :else x))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;client;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn unqualify-msg [msg]
  (-> msg
      (update 0 u/unqualify)
      (update 1 (comp deep-unqualify convert/from-ib))))

(defn process-messages [client reader signal]
  (while (.isConnected client)
    (.waitForSignal signal)
    (.processMsgs reader)))

(defn client
  "Takes an optional global message handler function and returns a map
  which represents an IB api client. The global message handler is
  called on all received messages before any added handlers. Unlike
  added handlers which are used for side effects, the global handler
  must return a message that all added handlers are called on "
  [& [global-handler]]
  (let [handlers   (atom #{})
        handle-message
        (fn [msg]
          (let [msg (-> msg
                        unqualify-msg
                        (cond-> global-handler global-handler))]
            (doseq [f @handlers]
              (try
                (f msg)
                (catch Throwable t
                  (log/error t "Error handling message" msg))))))
        wrap       (wrapper handle-message)
        sig        (EJavaSignal.)
        ecs        (EClientSocket. wrap sig)
        next-id    (atom 0)
        next-id-fn #(swap! next-id inc)] ;;todo: seperate order ids?
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
        ib-args (walk-qualified convert/to-ib spec-key arg-map)]
    (u/invoke (:ecs conn)
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
