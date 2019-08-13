(ns iboga.wip2
  (:require [iboga.core :as ib :refer :all]
            [medley.core :as m]))

(defn mkadder
  "Returns a function which, given a value, adds that value the the atom a with the reducing function rf. If the result is reduced, deliver the dereferenced result to promise p."
  [a p rf]
  (fn [x]
    (let [result (rf a x)]
      (if (reduced? result)
        (do (deliver p @@result) @result) ;;what to return?
        result))))

(defn acc
  "Accumulates state in an atom subject to a transducer. returns a map
  with the keys :add!, :a and :p. Use the :add! function to add
  state. :p is a promise which will be delivered the state in a when
  rf is realized"
  ([xf rf] (acc xf rf (rf)))
  ([xf rf init]
   (let [a       (atom init)
         swapper (fn [acc x] (doto acc (swap! rf x)))
         rf      (xf swapper)
         p       (promise)]
     {:add! (mkadder a p rf) :a a :p p})))

(defn id-filterer [[req-id argmap]]
  (filter (fn [[msg-key msg]]
            (= ((qualify-key req-id :id) argmap)
               (:id msg)))))

(defmulti filterer
  "Takes a qualified request and returns a transducer which filters
  incoming messages to respond to for that request"
  (fn [[msg-key argmap]] msg-key))

(defmulti taker
  "Takes a qualified request and returns a transducers which 'takes'
  incoming messages to respond to for that request. Should return a reduced value"
  (fn [[msg-key argmap]] msg-key))

(defmulti cleanup
  "Cleanup code to run after request has been 'finished'"
  (fn [conn [msg-key argmap]] msg-key))

(defmethod filterer :default [[req-key argmap]] nil)

(defmethod taker :default [[req-key argmap]] nil)

(defmethod cleanup :default [conn [req-key argmap]] nil)

;;note the awkwardness here: things on the request side are qualified keys but
;;messages coming in are all unqualified. This is because we're using the
;;regular handler system which unqualifies everything for ease of use.

(defmethod filterer :iboga.req/historical-data [req]
  (id-filterer req))

(defmethod taker :iboga.req/historical-data [[req-id argmap]]
  (m/take-upto
   (fn [[msg-key msg]]
     (if (:iboga.req.historical-data/update? argmap)
       (and (= msg-key :error)
            (:error-msg msg)
            (.contains (:error-msg msg) "API historical data query cancelled"))
       (= msg-key :historical-data-end)))))

(defmethod taker :iboga.req/contract-details [[req-id argmap]]
  (m/take-upto
   (fn [[msg-key msg]]
     ;;error?
     (= msg-key :contract-details-end))))

(defmethod filterer :iboga.req/contract-details [req]
  (id-filterer req))


(defmethod filterer :iboga.req/head-timestamp [req]
  (id-filterer req))

(defmethod taker :iboga.req/head-timestamp [[req-key argmap]]
  (take 1))

(defmethod cleanup :iboga.req/head-timestamp [conn [req-key argmap]]
  (req conn [:cancel-head-timestamp {:id (:iboga.req.head-timestamp/id argmap)}]))

(defn add-id [args id]
    (if (vector? args)
      (into [id] args)
      (assoc args :id id)))

(def ^:dynamic *timeout* 2000)

;;TODO: error handling

(defn sync-req* [conn qreq]
  (let [fil              (filterer qreq)
        tak              (taker qreq)
        _                (assert (and fil tak)
                                 (str "sync-req not available for " qreq))
        xf               (comp fil tak)
        {:keys [add! p]} (acc xf conj)
        h                (fn [msg] (add! msg))]
    (add-handler conn h)
    (req* conn qreq)
    (let [result (deref p *timeout* ::timeout)]
      (when (= result ::timeout) (throw (ex-info "sync-req timeout" (second qreq))))

      (remove-handler conn h)
      (cleanup conn qreq)
      result)))

(defn sync-req [conn request & [opt]]
  (let [{:keys [auto-id]} opt]
    (-> request
        (cond-> auto-id (update 1 add-id (next-id conn)))
        qify
        (->> (sync-req* conn)))))

;;(sync-req conn [:historical-data [TSLA]] {:auto-id true})
;;(sync-req conn [:head-timestamp [1234 TSLA]])
;;(sync-req conn [:contract-details [123 TSLA]])

