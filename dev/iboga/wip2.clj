(ns iboga.wip2
  (:require [iboga.core :as ib :refer :all]
            [medley.core :as m]))

(defn mkadder
  "Returns a function which, given a value, adds that value the the atom
  a with the reducing function rf. If the result is reduced, deliver
  the dereferenced result to promise p."
  [a p rf]
  (fn [x]
    (let [result (rf a x)]
      (if (reduced? result)
        (deliver p @@result)
        result)
      result)))

(defn acc
  "Accumulates state in an atom subject to a transducer. Returns a map
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

;;payload/taker could have special meansings for keyword
;;(ie keep key for payload and take until keyword msg-key for taker)
;;taker could also have an interger for take x, though that would usually be one?
;;todo: rename to clarify what should be a xf and what isn't?
(def req-helpers
  {:historical-data
   {:taker   (fn [{:keys [args] :as ctx}]
               ;;todo: if update is true, should probably just return nil as there
               ;;is no longer a valid sync request
               (m/take-upto
                (fn [[msg-key msg]]
                  (if (:update? args)
                    (and (= msg-key :error)
                         (:error-msg msg)
                         (.contains (:error-msg msg) "API historical data query cancelled"))
                    (= msg-key :historical-data-end)))))
    :payload (fn [_ctx] (keep (comp :bar second)))}


   :contract-details
   {:taker   (fn [{:keys [req-id args]}]
               (m/take-upto
                (fn [[msg-key msg]]
                  ;;error?
                  (= msg-key :contract-details-end))))
    :payload (fn [_ctx] (keep (comp :contract-details second)))}

   :head-timestamp
   {:taker (fn [_ctx] (take 1))
    :payload (fn [_ctx] (keep (comp :head-timestamp second)))
    :cleanup (fn [{:keys [conn req-key args]}]

               ;;head-timestamp needs to be cancelled or else the id cannot be
               ;;used again. This is unlike details/history which free the id
               ;;after results. Probably a bug in IB's api server.
               (req conn [:cancel-head-timestamp {:id (:id args)}]))}})

(defn id-filterer [{:keys [req-key args] :as ctx}]
  (filter (fn [[msg-key msg]]
            (= (:id args) (:id msg)))))

(defn filterer [{:keys [req-key args] :as ctx}]
  (let [f (get-in req-helpers [req-key :filterer])]
    (cond
      f          (f ctx)
      (:id args) (id-filterer ctx))))

(defn taker [{:keys [req-key] :as ctx}]
  (let [f (get-in req-helpers [req-key :taker])]
    (when f (f ctx))))

(defn synchronizer
  "Given a requst ctx, returns a transducer which filters appropriate
  responses and is reduced upon the responses indicating completion"
  [ctx]
  (let [fil (filterer ctx)
        tak (taker ctx)
        _   (assert (and fil tak)
                    (str "sync-req not available for " ctx))]
    (comp fil tak)))

(defn cleanup
  "Given a context, calls the :cleanup functions for that context"
  [ctx]
  (when-let [f (get-in req-helpers [(:req-key ctx) :cleanup])]
    (f ctx)))

(defn payload
  "Given a ctx, returns a transducer which gets only the 'data payload'
  for the request type and context"
  [ctx]
  (when-let [f (get-in req-helpers [(:req-key ctx) :payload] ctx)]
    (f ctx)))

(defn comp-response-xf
  "Given a context and a function which takes a context and returns a
  transducer, calls `comp` on current :response-xf of the context and
  the returned transducer, or sets response-xf if it doesn't yet
  exist. A 'response-xf' describes the transformation of a stream of all
  received messages to a response"
  [{:keys [response-xf] :as ctx} xf-fn]
  (let [xf        (xf-fn ctx)
        response-xf (if response-xf (comp response-xf xf) xf)]
    (assoc ctx :response-xf response-xf)))

(defn sync-response [{:keys [response-xf] :as ctx}]
  (comp-response-xf ctx synchronizer))

(defn payload-response [{:keys [req-key response-xf] :as ctx}]
  ;;TODO: handle case where no f exists, just abort 
  (let [f (get-in req-helpers [req-key :payload] ctx)]
    (if f
      (comp-response-xf ctx f)
      ctx)))

(defn on-realized-response [{:keys [response-atom response-prom] :as ctx} f]
  (add-watch response-atom (gensym)
             (fn [k r o n]
               (when (realized? response-prom)
                 (f ctx)
                 (remove-watch r k))))
  ctx)

(defn acc-response [{:keys [conn response-xf] :as ctx}]

  (let [{:keys [add! p a]} (acc response-xf conj)
        h                  (fn [msg] (add! msg))

        ctx (assoc ctx :response-prom p :response-atom a)]
    (add-handler conn h)
    (on-realized-response ctx (fn [_] (println "removing handler") (remove-handler conn h)))
    (on-realized-response ctx (fn [ctx] (println "cleanup") (cleanup ctx)))
    ctx))

(defn add-id* [args id]
  (if (vector? args)
    (into [id] args)
    (assoc args :id id)))

(defn add-id [{:keys [conn args] :as ctx}]
  (let [id (next-id conn)]
    (update ctx :args add-id* id)))


(comment

 (def SPY {:local-symbol "SPY" :sec-type "STK" :exchange "SMART" :currency "USD"})

 (def log (atom []))

 (def conn (ib/client (fn [msg] (swap! log conj msg))))

 (ib/connect conn "localhost" 7497)

 (defn smart-req [conn req]
   (-> (req-ctx conn req)
       add-id          ;;force-adds an id to a request
       prep-req        ;;standard core prep
       sync-response   ;;synchronous response
       payload-response ;;only return payloads
       acc-response     ;;accumulator for responses
       send-req         ;;send request
       :response-prom
       deref))  


 (smart-req conn [:historical-data [SPY]])

 (smart-req conn [:contract-details [SPY]])

 ;;note: returns vector of results even though it will only ever be one result 
 (smart-req conn [:head-timestamp [SPY]])
 )
