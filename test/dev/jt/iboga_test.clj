(ns dev.jt.iboga-test
  (:require [dev.jt.iboga :as ib :refer :all]
            [clojure.java.data :as jd]
            [clojure.test :refer :all])
  (:import [com.ib.client Contract ComboLeg Bar ContractDetails Order TagValue
            Types Types$Action]
           [java.util ArrayList]))



(def con1 {::ib/type :Contract
           :symbol     "SPY"
           :secType    "BAG"
           :currency   "USD"
           :exchange   "SMART"
           :comboLegs
           [{::ib/type :ComboLeg
             :conid      1
             :ratio      1
             :action     "SELL"
             :exchange   "SMART"}
            {::ib/type :ComboLeg
             :conid      2
             :ratio      1
             :action     "BUY"
             :exchange   "SMART"}]})

(def o1 {::ib/type   :Order
         :action       "BUY"
         :orderType    "MKT"
         :totalQuantity     2.0
         :algoStrategy "Adaptive"
         :algoParams [{::ib/type :TagValue :tag "adaptivePriority" :value "Urgent"}]})

;;(.algoStrategy (Order.) "Adaptive")

;;(first (.algoParams (to-ib o1)))

(deftest test-to-java
  (let [jcon (to-ib  con1)]
    (is (instance? Contract jcon))
    (is (= (.symbol jcon) "SPY"))
    (is (instance? ArrayList (.comboLegs jcon)))
    (is (instance? ComboLeg (first (.comboLegs jcon))))
    ;;note: does this vary? It could be string or enum?
    ;;(is (= "SELL" (.action (first (.comboLegs jcon)))))
    (is (= com.ib.client.Types$Action/SELL
          (.action (first (.comboLegs jcon))))))

  (let [ocon (to-ib o1)]
    (is (instance? ArrayList (.algoParams ocon)))
    (is (instance? TagValue (first (.algoParams ocon))))))

(run-tests)


#_(let [o (Order.)]
  ((->
     (->> (.getDeclaredMethods Order)
          (filter probably-setter?)
          (group-by (memfn getName))
          
          )
     (get "algoStrategy")
     (->> (filter (fn [^Method setter]
                    (= String (first (.getParameterTypes setter)))))
          first
          make-setter-fn)
     )
   o "Adaptive")
  (.getAlgoStrategy o))





