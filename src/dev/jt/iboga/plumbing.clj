(ns dev.jt.iboga.plumbing
  "Intermediate API that hides some of the lower level async channel
  plumbing when it's not directly needed. Provides sane defaults for
  the 80% case. This is particularly useful for interactive research
  and development.


  Global incoming connection and channel apparatus, to handle
  incoming messages from TWS, with sane defaults for the 80% case:

  * A single globally shared connection is made when (start!) is
    called, stored in plumbing/connection as an atom.

  * plumbing/ib-mult is provided to tap as the main interface point
    for receiving messages from TWS. It is a multiplexed channel copying
    all incoming messages.

  * All incoming messages are printed to STDOUT by default, for
    ease of development and debugging.
    TODO: Add thread-local var to toggle printing of messages on and off.

  * sync! is provided as a convenience wrapper, to easily make
    synchronous API requests to TWS with a timeout.

  
  Of course, you can always skip the plumbing namespace and build your
  own custom channel structures where performance is critical, where
  multiple connections to TWS are needed to make a high volume of
  market data requests, or for other special cases. Copying the
  apparatus here may be a good starting point.

  Please note that this is experimental alpha code. Interfaces and
  semantics are subject to change. Feedback on what works and what
  could be more useful in some other way is welcome.
  "
  (:require
   [dev.jt.iboga :as ib]   
   [dev.jt.iboga.lab :as lab]
   [dev.jt.iboga.util :as u]
   [clojure.core.async :as async :refer [<!! >!! chan]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;
;;;;;;;;
;;;;;;;; Global connection management
;;;;;;;;
;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Global incoming message buffer: ib-ch receives all messages as they
;; come in from TWS.
(defonce ib-ch (chan (async/sliding-buffer 1024)))


;; Global incoming message multiplexer: ib-mult allows other code to
;; create taps on all incoming messages.
(defonce ib-mult (async/mult ib-ch))


(defn handle-message!
  "Receives incoming messages from TWS and puts them onto ib-ch."
  [msg]
  (async/put! ib-ch msg))


;; Global default client map with sane defaults for the 80% case.
(defonce client (ib/client {::ib/handler handle-message!}))

;; Debugging and development tool: prints all incoming messages to STDOUT.
;; TODO: Add var to toggle printing on and off.
(defonce print-all (lab/on {::lab/recv-mult ib-mult} println))

;; Atom to hold the shared global connection
(defonce connection (atom nil))

(defn start!
  "Creates a new connection to TWS and stores it in the connection
  atom. Your code must call start! before using other plumbing
  functions."
  ([] (start! client "localhost" lab/dflt-paper-port (rand-int (Integer/MAX_VALUE))))
  ([client hostname port connection-id]
   (reset! connection (ib/connect client hostname port connection-id))))

;; TODO: Add clean connection shutdown function here.
(defn stop!
  []
  (println "Not implemented yet"))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;
;;;;;;;;
;;;;;;;; Synchronous request tools
;;;;;;;;
;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn sync!*
  "Executes function f -- typically an API call made to TWS -- in a
  context that waits up to timeout milliseconds for a stream of
  response messages from TWS.

  f is called with the client map and the request-id to use when
  making the call. f should not block.

  Returns a vector of messages, or nil if a timeout happens.

  NB: Alpha code; in particular, the semantics of what happens at a
  timeout event are subject to change in a future version as we gain
  experience with what is useful in that scenario.
  "
  [client timeout f]
  (let [id  (ib/next-req-id!)
        xf (comp (lab/match-ids id)
                 (u/until-end))
        ch (chan (async/sliding-buffer 10) xf)
        result-ch (async/into [] ch)]
    (async/tap ib-mult ch)
    (f client id)
    (async/alt!!
      result-ch ([result-msgs] result-msgs)
      (async/timeout timeout) ([] (println "timeout during IB sync request")))))


(defmacro sync!
  "Executes a synchronous request to TWS that waits up to timeout ms for a reply.

  The symbols `client` and `req-id` are bound to the iboga client map and
  the request ID to be used.

  Returns a vector of messages received from TWS in reply to the call,
  or nil if a timeout happens.

  NB: Alpha code; in particular, the semantics of what happens at a
  timeout event are subject to change in a future version as we gain
  experience with what is useful in that scenario."
  [client timeout & forms]
  `(sync!* ~client
             ~timeout
             (fn [~'client ~'req-id]
               ~@forms)))


