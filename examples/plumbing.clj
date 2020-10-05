(ns plumbing
  "Examples of how to use the mid-level plumbing and contracts APIs.

   Expects a TWS API server to be running on localhost:7497, the
   default paper trading port.
  
   Please note these APIs are experimental alpha code; the interfaces
   and semantics may change."
  (:require [dev.jt.iboga.plumbing :as p]
            [dev.jt.iboga.contracts :as c]))


;; Maximum time to await asynchronous replies from TWS, in milliseconds.
(def timeout 3000)

(defonce connection-started (p/start!))

;; RIG has a small options chain, which makes it a nice demo.
(def rig (c/get-contract-details p/client
                                 timeout
                                 (c/us-stock "RIG")))

(def rig-options (c/get-options-for p/client
                                    timeout
                                    (c/us-stock "RIG")))
