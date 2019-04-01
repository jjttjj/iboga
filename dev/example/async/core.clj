(ns example.async.core
  (:require [iboga.async :as ib])
  (:import (java.time Instant LocalDate LocalDateTime LocalTime
                      Period ZonedDateTime ZoneId ZoneOffset)
           java.time.format.DateTimeFormatter
           java.sql.Timestamp
           com.ib.client.Contract))



;;https://dev.clojure.org/jira/browse/CLJ-1451?page=com.atlassian.jira.plugin.system.issuetabpanels:all-tabpanel
(defn take-until
  ([pred]
   (fn [rf]
     (fn
       ([] (rf))
       ([result] (rf result))
       ([result input]
        (if (pred input)
          (ensure-reduced (rf result input))
          (rf result input))))))
  ([pred coll]
   (lazy-seq
    (when-let [s (seq coll)]
      (if (pred (first s))
        (cons (first s) nil)
        (cons (first s) (take-until pred (rest s))))))))

(defn req-id [msg] (or (:reqId msg) (:tickerId msg)))

(def ib-datetime-formatter (DateTimeFormatter/ofPattern "yyyyMMdd[  HH:mm:ss]"))

(defn parse-time [s]
  (if (= (count s) 8)
    (-> s (LocalDate/parse ib-datetime-formatter) (LocalDateTime/of LocalTime/MIDNIGHT))
    (-> s (LocalDateTime/parse ib-datetime-formatter))))

(defn mkbar [bar]
  {:time   (-> (.time bar) parse-time Timestamp/valueOf)
   :open   (.open bar)
   :high   (.high bar)
   :low    (.low bar)
   :close  (.close bar)
   :volume (.volume bar)
   :count  (.count bar)
   :wap    (.wap bar)})


(def log (atom []))
(def responses (atom []))

(def connection
  (ib/async-conn "192.168.1.155" 7496 (rand-int (Integer/MAX_VALUE))
                 #(do ;;(println "default msg handler:" %)
                      (swap! log conj %))))

(def ES (doto (Contract.)
          (.secType "CONTFUT")
          (.exchange "GLOBEX")
          (.symbol "ES")))


(ib/req! connection :reqHistoricalData
         [(rand-int (Integer/MAX_VALUE))
          ES
          (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yyyyMMdd[  HH:mm:ss]"))
          "100 D" "5 mins"
          "TRADES"
          0
          1
          false
          nil]
         (fn [argmap]
           (comp ;;(filter (fn [msg] (= (:type msg) :historicalData)))
            (filter #(= (req-id argmap) (req-id %))) ;;weird tickerId vs reqId names
            (map #(if (:bar %)
                    (update % :bar mkbar)
                    %))
            (take-until #(= (:type %) :historicalDataEnd))
            (map #(do ;;(println "historical data received: " %)
                      (swap! responses conj %))))))
