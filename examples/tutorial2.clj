(ns tutorial2
  (:require [clojure.spec.alpha :as s]
            [iboga.core :as ib]
            [iboga.wip :as wip])
  (:import [java.time LocalDate]))

;;a log for all incoming messages
(def log (atom []))

;;We create a conn and give it a default handler which logs every message
(def conn (ib/client #(swap! log conj %)))

(ib/connect conn "localhost" 7497)

;;Here we will use some of the higher level functionality in iboga.wip to get
;;synchronous results for some requests without having to manually manage the
;;requests/responses. For example

(def contract {:local-symbol "SPY"
               :sec-type "STK"
               :exchange "SMART"
               :currency "USD"})

;;synchronously get the results of contract.
(wip/contract-details conn [contract])

(s/describe (ib/req-spec-key :place-order))

(def mkt-order {:action "BUY" :order-type "MKT" :total-quantity 10})

(def lmt-order {:action "BUY" :order-type "LMT" :total-quantity 10 :lmt-price 200})

(ib/req conn [:place-order [(wip/next-order-id conn) contract mkt-order]])

(ib/req conn [:place-order [(wip/next-order-id conn) contract lmt-order]])

(def adaptive-mkt-order {:action "SELL"
                         :order-type "MKT"
                         :total-quantity 10
                         :algo-strategy "Adaptive"
                         :algo-params [{:tag "adaptivePriority" :value "Urgent"}]})

(ib/req conn [:place-order [(wip/next-order-id conn) contract adaptive-mkt-order]])

(def c1 (-> (wip/contract-details conn [{:symbol   "SPY"
                                             :sec-type "OPT"
                                             :currency "USD"
                                             :right    "PUT"
                                             :exchange "BATS"
                                              :strike   200.0
                                             :last-trade-date-or-contract-month
                                             (LocalDate/parse "2021-01-15")}])
            first
            :conid))

(def c2 (-> (wip/contract-details conn [{:symbol   "SPY"
                                              :sec-type "OPT"
                                              :currency "USD"
                                              :right    "PUT"
                                              :exchange "BATS"
                                              :strike   250.0
                                              :last-trade-date-or-contract-month
                                              (LocalDate/parse "2021-01-15")}])
            first
            :conid))

(ib/req conn
     [:place-order
      [(wip/next-order-id conn)
       {:symbol   "SPY"
        :sec-type "BAG"
        :currency "USD"
        :exchange "SMART"
        :combo-legs
        [{:conid c1 :ratio 1 :action "SELL" :exchange "SMART"}
         {:conid c2 :ratio 1 :action "BUY" :exchange "SMART"}]}
       {:action         "BUY" :order-type "MKT"
        :total-quantity 2
        :smart-combo-routing-params 
        [{:tag "NonGuaranteed" :value 0}]}]])
