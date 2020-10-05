(ns dev.jt.iboga.contracts
  "Convenience functions to manipulate TWS contracts.

   Please note that this is alpha code; interfaces and semantics are
   subject to change. Feedback on what works and what could be more
   useful in some other way is welcome."
  (:require
   [dev.jt.iboga :as ib]
   [dev.jt.iboga.plumbing :as p]))


(defn us-stock
  "Returns a TWS contract for a US-listed stock on the SMART exchange
  with the symbol sym (e.g. \"AAPL\", \"MSFT\")."
  [sym]
  {:symbol      sym
   :secType     "STK"
   :localSymbol sym
   :currency    "USD"
   :exchange    "SMART"
   ::ib/type    :Contract})


(defn get-contract-details*
  "Returns the full TWS specification for the given contract.

  Prefer the memoized version get-contract-details in most cases."
  [client timeout contract]
  (some-> (p/sync! client
                   timeout
                   (ib/send! client
                             {::ib/op   :reqContractDetails
                              :reqId    req-id
                              :contract contract}))
          first
          :contractDetails))

(def get-contract-details (memoize get-contract-details*))


(defn get-options-for*
  "Returns all available options for the given contract.
  Prefer the memoized version get-options-for in most cases.

   May take up to (* 2 timeout) ms total, as this wraps a call to
  get-contract-details."
  [client timeout contract]
  (if-let [contract-details (get-contract-details client timeout contract)]
    (p/sync! client
             timeout
             (ib/send! client
                       {::ib/op :reqSecDefOptParams
                        :reqId             req-id
                        :underlyingSymbol  (get-in contract-details [:contract :symbol])
                        :futFopExchange    ""
                        :underlyingSecType (get-in contract-details [:contract :getSecType])
                        :underlyingConId   (:conid contract-details)}))))


(def get-options-for (memoize get-options-for*))
