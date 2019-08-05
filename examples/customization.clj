(ns customization
  (:require [iboga.core :as ib]
            [clojure.spec.alpha :as s])
  (:import [java.time LocalDate LocalDateTime ZonedDateTime ZoneId]
           java.time.format.DateTimeFormatter))


(def log (atom []))
(def conn (ib/client (fn [msg] (swap! log conj msg))))
(ib/connect conn "localhost" 7497)

(def contract {:local-symbol "SPY" :sec-type "STK" :exchange "SMART" :currency "USD"})

;;Let's say we always want to work with ZonedDateTimes for historical data:

;;By default Iboga uses LocalDates LocalDateTimes for historical data.

;;If we try to pass :end a ZDT we get a spec error:

(ib/req conn [:historical-data {:id (rand-int 10000)
                                :contract contract :bar-size "1 hour"
                                :end (ZonedDateTime/now)}] )

;;Let's say you want to change this to use ZonedDateTime's instead, using the
;;system default timezone. We can do this with the `set-schema!` function.


;;First we must define a formatter to translate the java time object object to
;;the String IB needs.
;;See: http://interactivebrokers.github.io/tws-api/classIBApi_1_1EClient.html#a5eac5b7908b62c224985cf8577a8350c
(def ib-datetime-formatter (DateTimeFormatter/ofPattern "yyyyMMdd[  HH:mm:ss]"))

;;Now we set the to-ib attriute of :iboga.req.historical-data/end
(ib/set-schema! :iboga.req.historical-data/end
                {:to-ib      #(.format ib-datetime-formatter % )
                 :default-fn (fn [_argmap] (ZonedDateTime/now))})

;;we also define a spec, which will be checked if ib/validate-reqs is on
(s/def :iboga.req.historical-data/end #(instance? ZonedDateTime %))

;;We can now make our request with a ZDT:

(ib/req conn [:historical-data {:id       (rand-int 10000)
                                :contract contract :bar-size "1 hour"
                                :end      (ZonedDateTime/now)}])

;;But we still get back LDT in the historical data bars:
(->> @log
     (take-last 4)
     first         ;;get one of the historical data messages
     second        ;;get the msg-data
     :bar          ;;get the bar
     :time 
     type)
;;java.time.LocalDateTime


;;So we also need to parse :iboga.bar/time as a ZonedDateTime from the string IB provides
;;See: http://interactivebrokers.github.io/tws-api/classIBApi_1_1Bar.html#a2e05cace0aa52d809654c7248e052ef2

(defn parse-ib-time [x]
  (if (= (count x) 8) ;;breaks in year 10000
    (-> x
        (LocalDate/parse ib-datetime-formatter)
        (.atStartOfDay (ZoneId/systemDefault)))
    (-> x
        (LocalDateTime/parse ib-datetime-formatter)
        (ZonedDateTime/of (ZoneId/systemDefault)))))


;;note that we can pass `set-schema!` a tag, attribute key, and value as well
(ib/set-schema! :iboga.bar/time :from-ib parse-ib-time)

(ib/req conn [:historical-data {:id 123 :contract contract :bar-size "1 hour"}] )

(->> @log
     (take-last 4)
     first
     second
     :bar
     :time
     type)
;;java.time.ZonedDateTime
