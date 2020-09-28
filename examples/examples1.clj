(ns examples1
  (:require [dev.jt.iboga :as ib]
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

(def c1 (ib/client (ib/dflt-config)))
(def log (atom []))

;;ib/on attaches a handler to the connection. We can pass it an option :xf and/or :buf
;;; to set a transducer to pass all messages through, or a buffer.
(ib/on c1 #(println "Error: " (or (:errorMsg %) %)) {:xf (ib/match-op :error)})
(ib/on c1 #(swap! log conj %))
(ib/start c1)

;;(stop c1)

(ib/send! c1 {::ib/op :reqCurrentTime})
(print (last @log))


(def condeets
  (-> (ib/req! c1 {::ib/op   :reqContractDetails
                   :reqId    (id!)
                   :contract (stk "SPY")})
      ib/sync!
      (->> (keep :contractDetails))
      first))

(def SPY (:contract condeets))

(def hist
  (-> (ib/req! c1
        {::ib/op         :reqHistoricalData
         :tickerId       (id!)
         :contract       SPY
         :endDateTime    (u/format-ib-time (java.time.ZonedDateTime/now))
         :durationStr    "1800 S"
         :barSizeSetting "1 Secs"
         :whatToShow     "BID"
         :useRTH         1
         :formatDate     2
         :keepUpToDate   false})
      ib/sync!))


(def mkt
  (let [{::ib/keys [ch] :as resp} 
        (ib/req! c1
          {::ib/op             :reqMktData
           :tickerId           (id!)
           :contract           SPY
           :genericTickList    "233"
           :snapshot           false
           :regulatorySnapshot false
           :mktDataOptions     nil})]
    (go-loop []
      (when-some [x (<! ch)]
        (println x)
        (recur)))
    resp))

;;to stop 
(ib/cancel! mkt)
