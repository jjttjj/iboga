(ns iboga.client-test
  (:require [iboga.client :as ib]
            [clojure.test :refer :all]
            [clojure.tools.logging :as log])
  (:import [java.time LocalDate LocalDateTime]
           [com.ib.client Contract Order ComboLeg TagValue PriceIncrement]
           java.util.ArrayList))

(def complex-order
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
    [{:tag "NonGuaranteed" :value 0}]}})

(deftest test-qualify-map
  (is (= (ib/walk-qualified #(identity %2) :iboga.req/place-order complex-order)
         {:iboga.req.place-order/order-id 111
          :iboga.req.place-order/contract
          {:iboga.contract/symbol   "SPY"
           :iboga.contract/sec-type "BAG"
           :iboga.contract/currency "USD"
           :iboga.contract/exchange "SMART"
           :iboga.contract/combo-legs
           [{:iboga.combo-leg/conid    1
             :iboga.combo-leg/ratio    1
             :iboga.combo-leg/action   "SELL"
             :iboga.combo-leg/exchange "SMART"}
            {:iboga.combo-leg/conid    2
             :iboga.combo-leg/ratio    1
             :iboga.combo-leg/action   "BUY"
             :iboga.combo-leg/exchange "SMART"}]}
          :iboga.req.place-order/order
          {:iboga.order/action         "BUY"
           :iboga.order/order-type     "MKT"
           :iboga.order/total-quantity 2
           :iboga.order/smart-combo-routing-params 
           [{:iboga.tag-value/tag "NonGuaranteed" :iboga.tag-value/value 0}]}})))

(deftest test-unqualify-walk
  (is (= complex-order
         (ib/deep-unqualify
          (ib/walk-qualified #(identity %2)
                             :iboga.req/place-order complex-order)))))

(deftest test-to-ib
  (let [{my-order    :iboga.req.place-order/order
         my-contract :iboga.req.place-order/contract}
        (ib/walk-qualified ib/to-ib :iboga.req/place-order complex-order)

        {their-order    :iboga.req.place-order/order
         their-contract :iboga.req.place-order/contract}
        #:iboga.req.place-order
        {:order-id 111
         :contract (doto (Contract.)
                     (.symbol "SPY")
                     (.secType "BAG")
                     (.currency "USD")
                     (.exchange "SMART")
                     (.comboLegs
                      (ArrayList. [(doto (ComboLeg.)
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
      (is (= (ib/invoke their-contract prop (into-array []))
             (ib/invoke my-contract prop (into-array [])))))

    (doseq [prop ["action" "orderType" "totalQuantity" "smartComboRoutingParams"]]
      (is (= (ib/invoke their-order prop (into-array []))
             (ib/invoke my-order prop (into-array [])))))))

(deftest test-from-ib
  (is (= #:iboga.recv.market-rule
         {:market-rule-id   26
          :price-increments [#:iboga.price-increment
                             {:increment 0.01
                              :low-edge  0.0}]}
         (ib/from-ib
          #:iboga.recv.market-rule
          {:market-rule-id   26
           :price-increments (ArrayList. [(PriceIncrement. 0.0 0.01)])}))))
