(ns dev.jt.iboga.lab
  (:require [clojure.core.async :as a :refer [<! <!! >!! >! go go-loop chan put!
                                              alt! alts! alt!! alts!!
                                              poll! offer! take! put!]]
            [dev.jt.iboga :as ib]
            [dev.jt.iboga.util :as u]
            [clojure.string :as str])
  (:import java.time.Instant))

;;use spec?
#_
(defn validate-req [req]
  (assert (every? some? (map req (data/req->params (::ib/op req))))
    (str "missing args for req. needs: "
      (req->params (::ib/op req)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Async client
(def dflt-paper-port 7497)

(defn dflt-config []
  {::host      "localhost"
   ::port      dflt-paper-port
   ::client-id (rand-int (Integer/MAX_VALUE))})


(defn client [{::keys [host port client-id ] :as ctx
               :keys  [::buf ::out]}]
  {:pre [host port client-id]}
  (let [out  (or out (chan (or buf 10)))
        ib   (-> ctx
                 (assoc ::ib/handler (fn [msg] (a/put! out
                                                 (assoc msg
                                                   ::ts (Instant/now)
                                                   ::client-id client-id))))
                 ib/client)
        mult (a/mult out)]
    (-> ib
        (assoc
          ::recv-mult mult
          ::start-fn (fn client-start! []
                       (ib/connect ib host port client-id))
          ::stop-fn (fn client-stop! [] (ib/disconnect ib))))))

(defn tap [{::keys [recv-mult]} ch]
  {:pre [recv-mult]}
  (a/tap recv-mult ch))

(defn on [client f & [{:keys [xf buf]}]]
  (let [buf (or buf (a/sliding-buffer 2048))
        ch  (if xf (chan buf xf) (chan buf))]
    (go-loop []
      (when-some [x (<! ch)]
        (f x)
        (recur)))
    (tap client ch)
    #(a/close! ch)))

(defn start [{::keys [start-fn] :as ctx}]
  {:pre [start-fn]}
  (start-fn)
  ctx)

(defn stop [{::keys [stop-fn] :as ctx}]
  {:pre [stop-fn]}
  (stop-fn)
  ctx)

;;; Stream helpers

(def id-keys #{:reqId :requestId :orderId :tickerId :id})

(defn get-id [msg]
  (first (keep msg id-keys)))

(defn match-ids
  ([id]
   (filter (fn [msg] ((if (set? id) id #{id}) (get-id msg)))))
  ([id xs] (into [] (match-ids id) xs)))

(defn match-kv
  ([k v] (filter (fn [x] (= (get x k) v))))
  ([k v xs] (into [] (match-kv k v) xs)))

(defn match-op
  ([op]
   (filter (comp (if (set? op) op #{op}) ::ib/op)))
  ([op xs] (into [] (match-op op) xs)))

(defn end-at
  ([op]
   (u/take-upto (comp (if (set? op) op #{op}) ::ib/op)))
  ([op xs] (into [] (end-at op) xs)))


;;; Req transducers

;;; Should end messages be included in the response?

;;Note: match-ids will allow error messages, which just have an :id key, to
;;match relevant messages

(def req-op->end-op
  (->> (keys ib/recv->params)
       (keep (fn [k]
               (re-find #"(.+)End$" (name k))))
       (keep (fn [[end subj]]
               (when-let [reqs (->> (keys ib/req->params)
                                    (filter (fn [k]
                                              (and
                                                (= (str/lower-case (str "req" subj))
                                                  (str/lower-case (name k)))
                                                )))
                                    not-empty)]
                 (assert (= (count reqs) 1))
                 [(first reqs) (keyword end)])))
       (into {})))

(def req-op->cancel-op
  (->> ib/req->params
       keys
       (keep (fn [k] (re-find #"cancel(.*)$" (name k))))
       (keep (fn [[canc subj]]
               (when-let [reqs (->> (keys ib/req->params)
                                    (filter (fn [k] (= (name k) (str "req" subj))))
                                    not-empty)]
                 (assert (= (count reqs) 1))
                 [(first reqs) (keyword canc)])))
       (into {})))

(def req-op->id-key
  (->>
    (for [[k v] ib/req->params
          :let  [id-key (some id-keys v)]
          :when id-key]
      [k id-key])
    (into {})))

(def req-op->response-op
  {:reqManagedAccts :managedAccounts
   :reqIds          :nextValidId})

(def single-response-ops
  #{:reqManagedAccts
    :nextValidId})

;;; Cancellation messages

(defmulti cancel-req (fn [req] (::ib/op req)) :default ::default)

(defmethod cancel-req ::default [{::keys [op] :as req}]
  (when-let [canc-op (req-op->cancel-op op)]
    (let [idk (req-op->id-key op)]
      {::ib/op canc-op
       idk  (get req idk)})))

(defmethod cancel-req :reqMktDepth [req]
  {::ib/op          :cancelMktDepth
   :tickerId     (:tickerId req)
   :isSmartDepth (:isSmartDepth req)})

(defmethod cancel-req :reqAccountUpdates [req]
  (assoc req :subscribe false))

(defmulti response-xf (fn [req] (::ib/op req)) :default ::default)

(defmethod response-xf ::default [req] nil)

(defmethod response-xf :reqContractDetails [{:keys [reqId] :as req}]
  (comp (match-ids reqId)
        (end-at :contractDetailsEnd)
        (match-op :contractDetails)))

(defmethod response-xf :reqPositions [req]
  (comp (end-at :positionEnd)))

(defmethod response-xf :reqManagedAccts [req]
  (comp (match-op :managedAccounts) (take 1)))

(defmethod response-xf :reqIds [req]
  (comp (match-op :nextValidId) (take 1)))

;;; Todo: special case historical data when keepUpToDate is true
(defmethod response-xf :reqHistoricalData [req]
  (comp (match-ids (get-id req))
        (end-at :historicalDataEnd)
        (match-op :historicalData)))

(defn req! [ib {::keys [op buf xf ch post-xf] :as req}]
  (let [xf      (cond-> (or xf (response-xf req))
                  post-xf (comp post-xf))
        buf     (or buf (a/sliding-buffer 2048))
        ch      (cond ch    ch
                      xf    (chan buf xf)
                      :else (chan buf))
        canc-fn (when-let [canc-msg (cancel-req req)]
                  #(ib/send! ib canc-msg))]
    (tap ib ch)
    (ib/send! ib req)
    (cond-> {::ch ch ::req req}
      canc-fn (assoc ::cancel-fn canc-fn))))

(defn cancel! [response]
  (when-let [cancf (::cancel-fn response)]
    (cancf)))

(defn result+ [ch & {:keys [timeout]
                     :or   {timeout 2000}}]

  (let [ch (a/into [] ch)
        to (a/timeout timeout)]
    (a/alt!!
      ch ([x] x)
      to ([_] ::timeout))))

(defn timeout? [x]
  (identical? ::timeout x))

(defn sync! [{::keys [ch] :as resp} & {:keys [timeout]
                                       :or   {timeout 2000}}]
  (result+ ch :timeout timeout))
