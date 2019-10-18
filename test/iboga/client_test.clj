(ns iboga.client-test
  (:require [iboga.client :as ib]
            [clojure.test :refer :all]
            [clojure.tools.logging :as log])
  (:import [java.time LocalDate LocalDateTime]
           [com.ib.client Contract Order ComboLeg TagValue]
           java.util.ArrayList))

(deftest test-to-ib
  (let [{my-order    :iboga.req.place-order/order
         my-contract :iboga.req.place-order/contract}
        (ib/to-ib
         (ib/qualify-map :iboga.req/place-order
                         {:order-id 111
                          :contract
                          {:symbol   "SPY"
                           :sec-type "BAG"
                           :currency "USD"
                           :exchange "SMART"
                           :combo-legs
                           [{:conid 1 :ratio 1 :action "SELL" :exchange "SMART"}
                            {:conid 2 :ratio 1 :action "BUY" :exchange "SMART"}]}
                          :order
                          {:action         "BUY"
                           :order-type     "MKT"
                           :total-quantity 2
                           :smart-combo-routing-params 
                           [{:tag "NonGuaranteed" :value 0}]}}))


        {their-order    :iboga.req.place-order/order
         their-contract :iboga.req.place-order/contract}
        #:iboga.req.place-order
        {:order-id 111
         :contract (doto (Contract.)
                     (.symbol "SPY")
                     (.secType "BAG")
                     (.currency "USD")
                     (.exchange "SMART")
                     (.comboLegs (ArrayList. [(doto (ComboLeg.)
                                                (.conid 1)
                                                (.ratio 1)
                                                (.action "SELL")
                                                (.exchange "SMART"))
                                              (doto (ComboLeg.)
                                                (.conid 2)
                                                (.ratio 1)
                                                (.action "BUY")
                                                (.exchange "SMART"))])))
         :order    (doto (Order.)
                     (.action "BUY")
                     (.orderType "MKT")
                     (.totalQuantity 2)
                     (.smartComboRoutingParams
                      (ArrayList. [(TagValue. "NonGuaranteed" "0")])))}]
    ;;note: IB overrides .equals and theirs cannot be trusted at the object level.
    (doseq [prop ["symbol" "secType" "currency" "exchange" "comboLegs"]]
      (is (= (ib/invoke my-contract prop (into-array []))
             (ib/invoke their-contract prop (into-array [])))))

    (doseq [prop ["action" "orderType" "totalQuantity" "smartComboRoutingParams"]]
      (is (= (ib/invoke my-order prop (into-array []))
             (ib/invoke their-order prop (into-array [])))))))

(def SPY {:symbol "SPY" :sec-type "STK" :exchange "SMART" :currency "USD"})

;;tests a round trip message. `:market-rule` is simple but the result
;;covers a lot since it returns an array of "data objects"
(deftest test-send-and-recieve
  (let [ib-initialized? (promise)
        ib
        (ib/client (fn [[msg-key payload]]
                     (when (= msg-key :next-valid-id)
                       (deliver ib-initialized? true))
                     (when (and (= msg-key :error)
                                (not (= (.getMessage (:e payload))
                                        "Socket closed")))
                       (log/debug payload))))
        market-rule-msg (promise)]
    (ib/connect ib  "localhost" 4002)
    (deref ib-initialized? 2000 ::timeout)
    (ib/add-handler ib (fn [[msg-key _ :as msg]]
                         (when (= :market-rule msg-key)
                           (deliver market-rule-msg msg))))
    (ib/req ib [:market-rule {:market-rule-id 26}])
    (is (= (deref market-rule-msg 2000 ::timeout)
           [:market-rule
            {:market-rule-id   26
             :price-increments [{:increment 0.01 :low-edge 0.0}]}]))
    (ib/disconnect ib)))
