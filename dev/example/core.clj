(ns example.core
  (:require [iboga.core :as iboga]
            [example.constants :as c]))

(defn handler
  "Parses the message type of incoming messages"
  [msg]
  (assoc msg 0 (get c/incoming (Integer/parseInt (first msg)) (first msg))))

(defn ->ib
  "Takes a vector prefaced with an outgoing message type keyword, replaces that with the integer id IB uses as well as the message version we're using, then translates the resulting message to the byte array IB takes."
  [msg]
  (-> (get c/outgoing (first msg))
      ((juxt :code :version))
      (into (rest msg))
      iboga/msg->bytes))

(comment
 (def ib (iboga/socket "127.0.0.1" 7496 (comp prn handler iboga/bytes->strs)))

 (def send!  (:send-fn ib))
 (def close! (:close-fn ib))

 ;;figure out message format from: 
 ;;to ib: IBJts/source/JavaClient/com/ib/client/EClient.java
 ;;from ib: IBJts/source/JavaClient/com/ib/client/EDecoder.java
 ;;have fun!

 ;;start-api with client id 101
 (send! (->ib [:start-api 101 ""]))
 ;;or
 ;;(send! (iboga/msg->bytes [71 2 101 ""]))

 (send! (->ib [:current-time]))

 ;;requestMktData with ticker-id 123
 (send! (->ib [:market-data 123 nil nil "STK"
               nil nil nil nil "SMART" nil
               "USD" "AMZN" nil nil "" "" "" ""]))
 ;;or
 ;;(send! (iboga/msg->bytes [1 11 123 nil nil "STK" nil nil nil nil "SMART" nil "USD" "AMZN" nil nil "" "" "" ""]))

 ;;cancelMktData
 (send! (->ib [:cancel-market-data 123]))
 ;;or
 ;;(send! (iboga/msg->bytes [2 1 123]))

 ;;close socket
 ;;(close!)







 )
