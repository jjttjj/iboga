(ns examples1
  (:require [dev.jt.iboga :as ib]
            [dev.jt.iboga.lab :as lab]
            [dev.jt.iboga.util :as u]
            [clojure.core.async :as a :refer [<! <!! >!! >! go go-loop chan put!
                                              alt! alts! alt!! alts!!
                                              poll! offer! take! put!]]))

;;todo: post-xf?

(defn stk [sym]
  {:symbol      sym
   :secType     "STK"
   :localSymbol sym
   :currency    "USD"
   :exchange    "SMART"
   ::ib/type    :Contract})

(defonce ctr (atom 0))
(defn id! [] (swap! ctr inc))

(def ib-ch (chan (a/sliding-buffer 1024)))
(def ib-mult (a/mult ib-ch))

(def log (atom []))

(let [log-ch (a/tap ib-mult (a/chan (a/sliding-buffer 1024)))]
  (go-loop []
    (when-some [msg (<! log-ch)]
      (swap! log conj msg)
      (recur))))

(let [err-ch (a/tap ib-mult
                    (a/chan (a/sliding-buffer 1024)
                            (filter (fn [msg] (= (::ib/op msg) :error)))))]
  (go-loop []
    (when-some [msg (<! err-ch)]
      (println "Error:" (or (:errorMsg msg) msg))
      (recur))))

(defn h! [msg]
  (a/put! ib-ch msg))

(def c1 (ib/client {::ib/handler h!}))
(ib/connect c1 "localhost" lab/dflt-paper-port (rand-int (Integer/MAX_VALUE)))
;;(ib/disconnect c1)


(ib/send! c1 {::ib/op :reqCurrentTime})
(print (last @log))


;;; Synchronous requests

;;see what args :reqContractDetails expects:
;;(ib/req->params :reqContractDetails)


(def condeets
  (let [id (id!)
        req {::ib/op   :reqContractDetails
                  :reqId    id
                  :contract (stk "SPY")}
        xf (comp (lab/match-ids id)
                 (lab/end-at :contractDetailsEnd)
                 (lab/match-op :contractDetails))
        ;;alternatively, experimental multimethod works for some ops
        #_(lab/response-xf req)
        ch (chan (a/sliding-buffer 10) xf)
        result-ch (a/into [] ch)]
    (a/tap ib-mult ch)
    (ib/send! c1 req)
    (a/alt!!
      result-ch ([result-msgs] (keep :contractDetails result-msgs))
      (a/timeout 2000) ([] (println "timeout during contract details request")))))

(def SPY (:contract (first condeets)))

;;see what args :reqHistoricalData expects:
;;(ib/req->params :reqHistoricalData)

(def hist-bars
  (let [id        (id!)
        req       {::ib/op         :reqHistoricalData
                   :tickerId       id
                   :contract       SPY
                   :endDateTime    (u/format-ib-time (java.time.ZonedDateTime/now))
                   :durationStr    "1800 S"
                   :barSizeSetting "1 Secs"
                   :whatToShow     "BID"
                   :useRTH         1
                   :formatDate     2
                   :keepUpToDate   false}
        xf        (comp (lab/match-ids id)
                        (lab/end-at :historicalDataEnd)
                        (lab/match-op :historicalData))
        ;;alternatively, experimental multimethod works for some ops
        #_        (lab/response-xf req)
        ch        (chan (a/sliding-buffer 2000) xf)
        result-ch (a/into [] ch)]
    (a/tap ib-mult ch)
    (ib/send! c1 req)
    (a/alt!!
      result-ch ([result-msgs] (keep :bar result-msgs))
      (a/timeout 2000) ([] (println "timeout during historical data request")))))




(def mkt
  (let [id        (id!)
        req       {::ib/op             :reqMktData
                   :tickerId           id
                   :contract           SPY
                   :genericTickList    "233"
                   :snapshot           false
                   :regulatorySnapshot false
                   :mktDataOptions     nil}
        xf        (lab/match-ids id)
        ch (chan (a/sliding-buffer 1000) xf)]
    (a/tap ib-mult ch)
    (ib/send! c1 req)
    (go-loop []
      (when-some [x (<! ch)]
        (println "New Market Data:" x)
        (recur)))
    (fn stop-fn []
      (ib/send! c1 {::ib/op :cancelMktData
                    :tickerId id}))))

;;to stop 
(mkt)
