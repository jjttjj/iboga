(ns iboga.wip2
  (:require [iboga.core :as ib :refer :all]
            [medley.core :as m]))

(defn mkadder
  "Returns a function which, given a value, adds that value the the atom a with the reducing function rf. If the result is reduced, deliver the dereferenced result to promise p."
  [a p rf]
  (fn [x]
    (let [result (rf a x)]
      (if (reduced? result)
        (deliver p @@result)
        result)
      result)))

(defn acc
  "Accumulates state in an atom subject to a transducer. returns a map
  with the keys :add!, :a and :p. Use the :add! function to add
  state. :p is a promise which will be delivered the state in a when
  rf is realized"
  ([xf rf] (acc xf rf (rf)))
  ([xf rf init]
   (let [a       (atom init)
         swapper (fn [acc x] (swap! acc rf x) acc)
         rf      (xf swapper)
         p       (promise)]
     {:add! (mkadder a p rf) :a a :p p})))

(defn id-filterer [{:keys [req-key args] :as ctx}]
  (filter (fn [[msg-key msg]] (= (:id args) (:id msg)))))

(defmulti filterer
  "Takes a qualified request and returns a transducer which filters
  incoming messages to respond to for that request"
  (fn [ctx] (:req-key ctx)))

(defmulti taker
  "Takes a qualified request and returns a transducers which 'takes'
  incoming messages to respond to for that request. Should return a reduced value"
  (fn [ctx] (:req-key ctx)))

(defmulti cleanup
  "Cleanup code to run after request has been 'finished'"
  (fn [{:keys [conn req-key args]}] req-key))

(defmethod filterer :default [ctx] nil)

(defmethod taker :default [ctx] nil)

(defmethod cleanup :default [ctx] nil)

;;note the awkwardness here: things on the request side are qualified keys but
;;messages coming in are all unqualified. This is because we're using the
;;regular handler system which unqualifies everything for ease of use.

(defmethod filterer :historical-data [ctx]
  (id-filterer ctx))

(defmethod taker :historical-data [{:keys [req-id args]}]
  (m/take-upto
   (fn [[msg-key msg]]
     (if (:update? args)
       (and (= msg-key :error)
            (:error-msg msg)
            (.contains (:error-msg msg) "API historical data query cancelled"))
       (= msg-key :historical-data-end)))))

(defmethod taker :contract-details [{:keys [req-id args]}]
  (m/take-upto
   (fn [[msg-key msg]]
     ;;error?
     (= msg-key :contract-details-end))))

(defmethod filterer :contract-details [ctx]
  (id-filterer ctx))

(defmethod filterer :head-timestamp [ctx]
  (id-filterer ctx))

(defmethod taker :head-timestamp [{:keys [req-key args]}]
  (take 1))

(defmethod cleanup :head-timestamp [{:keys [conn req-key args]}]
  (req conn [:cancel-head-timestamp {:id (:id args)}]))

(defn add-id [args id]
    (if (vector? args)
      (into [id] args)
      (assoc args :id id)))

(def ^:dynamic *timeout* 2000)

;;TODO: error handling


(defn sync-enter [{:keys [conn req-key args] :as ctx}]
  (let [fil              (filterer ctx)
        tak              (taker ctx)
        _                (assert (and fil tak)
                                 (str "sync-req not available for " ctx))
        xf               (comp fil tak)
        {:keys [add! p]} (acc xf conj)
        h                (fn [msg] (add! msg))]
    (add-handler conn h)
    (assoc ctx
           :result-prom p
           :sync-handler h)))

(defn sync-leave [{:keys [conn req-key args result-prom sync-handler] :as ctx}]
  (let [result (deref result-prom *timeout* ::timeout)]
    (when (= result ::timeout)
      (throw (ex-info "sync-req timeout" args)))
    (remove-handler conn sync-handler)
    (cleanup ctx)
    (assoc ctx :result result)))

(defn wrap-sync [handler]
  (fn [ctx]
    (sync-leave (handler (sync-enter ctx)))))

(comment
  (defn sync-middleware [handler]
    (-> handler wrap-sync wrap-default-middleware))

  (def SPY {:local-symbol "SPY" :sec-type "STK" :exchange "SMART" :currency "USD"})

  (def historical
    (:result
     (req conn [:historical-data [1 SPY]] sync-middleware)))

  (def details
    (:result
     (req conn [:contract-details [1 SPY]] sync-middleware)))

  (def head-ts
    (:result
     (req conn [:head-timestamp [1 SPY]] sync-middleware))))

;;(sync-req conn [:historical-data [TSLA]] {:auto-id true})
;;(sync-req conn [:head-timestamp [1234 TSLA]])
;;(sync-req conn [:contract-details [123 TSLA]])

