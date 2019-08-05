(ns tutorial1
  (:require [clojure.spec.alpha :as s]
            [iboga.core :as ib]))


;;a log for all incoming messages
(def log (atom []))

;;We create a conn and give it a default handler which logs every message
(def conn (ib/client #(swap! log conj %)))

;;we connect to the conn
;;(please use a paper trade account, which defaults to port 7497)
(ib/connect conn "localhost" 7497)

;;we can now check the default connection messages


;;Now we can make requests to IB!
;;requests correspond to the java class EConn, but are given clojury names
;;See https://interactivebrokers.github.io/tws-api/classIBApi_1_1EConn.html

;;To make requests we call the ib/req function on the conn and pass it a
;;vector containing the keyword for the request type and the arguments for the
;;request. The arguments can either be a vector of ordered args or a map of
;;keyword spec-keyged args.

;;let's start with the most boring request and request the current time
;;https://interactivebrokers.github.io/tws-api/classIBApi_1_1EConn.html#ad1ecfd4fb31841ce5817e0c32f44b639
;;current-time takes zero arguments so we pass it an empty vector:
(ib/req conn [:current-time []])

;;check the response:
(last @log)

;;let's request some historical data.
;;first let's find out the full spec key for historical-data
(ib/req-spec-key :historical-data)

;;now let's look at what a historical-data request message should look like:
(s/describe (ib/req-spec-key :historical-data))

;;Here is the spec for the historical data request. Note that in the description
;;here the spec keys will be fully qualified but unqualified keys are used in
;;the request we make. So only use the last segment of the spec key in the
;;request.

;;In the description result note that some keys are required (listed under `:req`) and some are optional (under `opt`).

;;there are two required keys, :id and :contract
;;let's see what these are:
(s/describe (ib/req-spec-key :historical-data :id))
(s/describe (ib/req-spec-key :historical-data :contract))

;;we see the :id is a number and the :contract is a map with a bunch of optional
;;keys let's make a contract then a request.

;;note: it will take a little getting used to what IB allows for contracts of
;;various security types. For stocks, :local-symbol, :sec-type, currency
;;:exchange are usually needed. It's possible that with the help of clojure.spec, one day Iboga will handle the specification 

;;We can try to describe things all the way down.
;;Sometimes the result isn't useful, but it might help to try to `eval` the `s/form`
(s/describe :iboga.contract/sec-type) ;;=> (set (map str (values))) ;not useful,
;;can't eval
(eval (s/form :iboga.contract/sec-type)) ;;works to get all sec types
;; =>
;; #{"IOPT" "ICU" "FUND" "ICS" "CFD" "CONTFUT" "BILL" "FOP" "BSK" "CMDTY" "FUT"
;;   "BOND" "IND" "FWD" "FIXED" "None" "SLB" "BAG" "STK" "OPT" "NEWS" "WAR" "CASH"}

(def contract {:local-symbol "SPY" :sec-type "STK" :exchange "SMART" :currency "USD"})

(ib/req conn [:historical-data {:id 111 :contract contract}])

;;when a request has an id, the returned messages we get to our conn have
;;that same id when retrieving results, remember that they come in
;;the same format as our requests. A vector with the message type and a
;;payload. So we want to filter our log for only messages with a second item
;;being equal to our :id
(def result1 (filter #(= 111 (:id (second %))) @log))
;;result types
(set (map first result1)) ;;#{:historical-data :historical-data-end}
(->> result1 )

;;Now let's get one day of 5 minute bars. You can see try to check the possible
;;values for the arguments:
(eval (s/form :iboga.req.historical-data/duration)) ;;not useful, oh well
(eval (s/form :iboga.req.historical-data/bar-size)) ;;all options for bar sizes

;;see also:
;;http://interactivebrokers.github.io/tws-api/historical_bars.html#hd_duration

(ib/req conn [:historical-data {:id       222
                                :contract contract
                                :duration "1 D"
                                :bar-size "5 mins"}])

(->>
 @log
 ;;filter results
 (filter #(= 222 (:id (second %)))) 
 ;;remove the results without a bar (ie remove the :historical-data-end message)
 (keep #(:bar (second %)))
 (map (fn [bar] [(str (.toLocalTime (:time bar))) (:close bar)])))

;;One final note is that we can always pass arguments as a vector instead of a
;;map. To check the order of the arguments, let's s/describe again:
(s/describe (ib/req-spec-key :historical-data))
;;The order of the vector arguments must always be the :req args in the order
;;they are described, followed by the :opt args in the order they are described.
;;Any number of optional arguments can be omitted from the end of the vector,
;;but to use an argument you must include all optional arguments prior to it.

;;So we can make an identical request to the previous one, but we need to add an
;;:end value because that is the first :opt argument and we can't omit it while
;;also including subsequent arguments
(s/describe :iboga.req.historical-data/end)
(ib/req conn [:historical-data [223
                                contract
                                (java.time.LocalDateTime/now)
                                "2 D"
                                "1 day"]])

;;This is all nice but we don't really want to always work with an ever growing
;;log atom. We can also attach and remove handlers to the conn.

(defn bar-handler [[msg-key data :as msg]]
  (when (:bar data)
    (println (:bar data))))

(ib/add-handler conn bar-handler)

(ib/req conn [:historical-data {:id       333
                                :contract contract
                                :duration "1 D"
                                :bar-size "1 hour"}])

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
  (ib/req conn [:historical-data {:id       req-id
                                  :contract contract
                                  :duration "1 D"
                                  :bar-size "1 hour"}])
  (deref result 1000 ::timeout))
