(ns tutorial1
  (:require [clojure.spec.alpha :as s]
            [iboga.core :as ib])
  (:import [java.time LocalDateTime]))


;;a log for all incoming messages
(def log (atom []))

;;We create a conn and give it a default handler which logs every message
(def conn (ib/client #(swap! log conj %)))

;;we connect to the conn
;;(please use a paper trade account, which defaults to port 7497)
(ib/connect conn "localhost" 7497)

;;we can now check the default connection messages
@log

;;Now we can make requests to IB!
;;requests correspond to the java class EConn, but are given clojury names
;;See https://interactivebrokers.github.io/tws-api/classIBApi_1_1EConn.html

;;To make requests we call the ib/req function on the conn and pass it a
;;vector containing the keyword for the request type and an arguments map

;;let's start with the most boring request and request the current time
;;https://interactivebrokers.github.io/tws-api/classIBApi_1_1EConn.html#ad1ecfd4fb31841ce5817e0c32f44b639
;;current-time takes zero arguments so we pass it an empty vector:
(ib/req conn [:current-time {}])

;;check the response:
(last @log)

;;let's request some historical data.
;;first let's find out the full spec key for historical-data
(ib/req-spec-key :historical-data)

;;now let's look at what a historical-data request message should look like:
(s/describe (ib/req-spec-key :historical-data))

;;Here is the spec for the historical data request. Note that in the description
;;here the spec keys will be fully qualified but since they are `:req-un` the
;;namespace segment is not require. So only use the last segment of the spec key
;;in the request.

;;let's see what the specs for the first two keys are:
(s/describe (ib/req-spec-key :historical-data :id))
(s/describe (ib/req-spec-key :historical-data :contract))

;;we see the :id is a number and the :contract is a map with a bunch of optional
;;keys let's make a contract then a request.

;;note: it will take a little getting used to what IB allows for contracts of
;;various security types. For stocks, :local-symbol, :sec-type, currency
;;:exchange are usually needed. It's possible that with the help of
;;clojure.spec, one day Iboga will handle the specification

;;let's try a few more:
(s/form :iboga.req.historical-data/duration) ;;not useful, oh well
(s/form :iboga.req.historical-data/bar-size) ;;all options for bar sizes

(s/describe :iboga.req.historical-data/end)
;;it's a nilable `LocalDateTime` because this should be nil when `:update?` is
;;true to receive updating bars every 5 seconds


(s/form :iboga.req.historical-data/format-date) ;;we can only use 1 for this

(s/form :iboga.req.historical-data/chart-options)
;;a coll of tag-values, but there is no documentaion from IB on what these
;;should be and you should always leave it empty


;;We can try to describe things all the way down.
(s/describe :iboga.contract/sec-type)
;; =>
;; #{"IOPT" "ICU" "FUND" "ICS" "CFD" "CONTFUT" "BILL" "FOP" "BSK" "CMDTY" "FUT"
;;   "BOND" "IND" "FWD" "FIXED" "None" "SLB" "BAG" "STK" "OPT" "NEWS" "WAR" "CASH"}

(def contract {:local-symbol "SPY" :sec-type "STK" :exchange "SMART" :currency "USD"})

(ib/req conn [:historical-data
              {:id 111
               :contract contract
               :end (LocalDateTime/now)
               :duration "1 M" ;;1 month
               :bar-size "1 day"
               :show "TRADES"
               :rth? true
               :format-date 1
               :update? false
               :chart-options []}])

;;when a request has an id, the returned messages we get to our conn have
;;that same id when retrieving results, remember that they come in
;;the same format as our requests. A vector with the message type and a
;;payload. So we want to filter our log for only messages with a second item
;;being equal to our :id
(def result1 (filter #(= 111 (:id (second %))) @log))
;;result types
(set (map first result1)) ;;#{:historical-data :historical-data-end}
;;just the bars:
(keep (comp :bar second) result1)

;;This is all nice but we don't really want to always work with an ever growing
;;log atom. We can also attach and remove handlers to the conn.

(defn bar-handler [[msg-key data :as msg]]
  (when (:bar data)
    (println (:bar data))))

(ib/add-handler conn bar-handler)

(ib/req conn [:historical-data
              {:id            111
               :contract      contract
               :end           (LocalDateTime/now)
               :duration      "2 D" ;;1 week
               :bar-size      "1 hour"
               :show          "TRADES"
               :rth?          true
               :format-date   1
               :update?       false
               :chart-options []}])

;;don't forget to remove the handler. 
(ib/remove-handler conn bar-handler)

;;we can use these features to get a synchronous result we can also use
;;the `next-id` function to get the next unused id for a conn

(let [result (promise)
      bars   (atom [])
      req-id (ib/next-id conn)
      f      (fn this [[msg-key {:keys [id bar]}]]
               ;;note: we need to name this function `this` so we can reference
               ;;it inside its own body to remove the handler
               (when (= id req-id)
                 (cond
                   bar
                   (swap! bars conj bar)
                   
                   (= msg-key :historical-data-end)
                   (do (deliver result @bars) (ib/remove-handler conn this)))))]
  (ib/add-handler conn f)
  (ib/req conn [:historical-data
                {:id            req-id
                 :contract      contract
                 :end           (LocalDateTime/now)
                 :duration      "2 D" ;;1 week
                 :bar-size      "1 hour"
                 :show          "TRADES"
                 :rth?          true
                 :format-date   1
                 :update?       false
                 :chart-options []}])
  (deref result 1000 ::timeout))
