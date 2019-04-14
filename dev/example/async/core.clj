(ns example.async.core
  (:require [iboga.async :as ib])
  (:import (java.time Instant LocalDate LocalDateTime LocalTime
                      Period ZonedDateTime ZoneId ZoneOffset)
           java.time.format.DateTimeFormatter
           java.sql.Timestamp
           com.ib.client.Contract))

(def log (atom []))

(def ES (doto (Contract.)
          (.secType "CONTFUT")
          (.exchange "GLOBEX")
          (.symbol "ES")))

#_(def connection
  (ib/async-conn "192.168.0.1" 7496 (rand-int (Integer/MAX_VALUE))
                 #(do ;;(println "default msg handler:" %)
                    (swap! log conj %))))



;;request for historical data
#_
(let [fin    (promise)
      data   (atom [])
      req-id (rand-int (Integer/MAX_VALUE))]
  (ib/request-historical-data connection
                              req-id
                              ES
                              (LocalDateTime/now) ;; no end date for live updates
                              "2 D" "1 hour"
                              "TRADES"
                              false ;;use rth
                              1 ;;"2" here can make it an int.
                              false ;;update
                              
                              {:data  #(swap! data conj %)
                               :error #(do (println %)
                                           (if (-> % :errorCode (= 162))
                                             (do (println "Error Code 162: redoing request with '3 D' duration")
                                                 
                                                 )
                                             (deliver fin nil)))
                               :update #(do (println "Update received:" %))
                               :end    #(do (println "Initial historical data received" %)
                                            (deliver fin @data))})
  @fin)


;;request-historical-data with live updates
#_
(let [fin     (promise)
      data    (atom [])
      updates (atom [])
      req-id  (rand-int (Integer/MAX_VALUE))
      _
      (ib/request-historical-data connection
                                  req-id
                                  ES
                                  nil ;; no end date for live updates
                                  "2 D" "1 hour"
                                  "TRADES"
                                  false ;;use rth
                                  1 ;;"2" here can make it an int.
                                  true ;;update
                                  {:data       #(swap! data conj %)
                                   :error      (fn [msg] (do (println "Error:" msg)))
                                   :end        (fn [msg] (do (println "Initial historical data received")
                                                             (deliver fin @data)))
                                   :update     #(do (println "Update received:" %)
                                                    (swap! updates conj %))
                                   :update-end (fn [msg] (println "Update ended:" msg))})
      result  {:initial-data @fin
               :updates      updates
               :req-id       req-id
               :cancel       (fn [] (ib/cancel-historical-data connection req-id))} ]


  (Thread/sleep 2000)
  ((:cancel result)))


;;symbol search
#_
(ib/request-matching-symbols
 connection (rand-int 1000)
 "SPY"
 {:data (fn [data]
          (run! #(println %) data))})
