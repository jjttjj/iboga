(ns iboga.impl
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str])
  (:import [java.time LocalDate LocalDateTime]
           java.time.format.DateTimeFormatter))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;convert;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ib-datetime-formatter (DateTimeFormatter/ofPattern "yyyyMMdd[  HH:mm:ss]"))

(defn format-ib-time [t] (.format ib-datetime-formatter t))

(defn parse-ib-time [x]
  (if (= (count x) 8) ;;breaks in year 10000
    (-> x (LocalDate/parse ib-datetime-formatter))
    (-> x (LocalDateTime/parse ib-datetime-formatter))))

(defn bool->bit [bool] (case bool true 1 false 0))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;spec helpers;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn local-date-time? [x]
  (instance? LocalDateTime x))

(defn date? [x]
  (instance? LocalDate x))

(s/def ::rth? boolean?)

(def rth? {:spec ::rth? :to-ib bool->bit})

(def format-date {:spec #{1}})

(def recv-date-str {:from-ib parse-ib-time})

(def contract-interval-formatter (DateTimeFormatter/ofPattern "yyyyMMdd[:HHmm]"))

;;the time zone of open/close is the contract's :time-zone-id in contract-dteails
;;https://interactivebrokers.github.io/tws-api/classIBApi_1_1ContractDetails.html#aa4434636ba3f3356fb804d9cf257f452
(defn parse-contract-hours [hours-str]
  (->> (str/split hours-str #";")
       (keep
        (fn [s]
          (if-not (.contains s "CLOSED")
            (->> (str/split s #"-")
                 (mapv #(LocalDateTime/parse % contract-interval-formatter))
                 (zipmap [:open :close]))
            {:closed (-> s
                         (str/replace #":CLOSED$" "")
                         (LocalDate/parse contract-interval-formatter))})))))

(def default-schema
  (merge

   #:iboga
   {:tag-value {:spec (s/keys :req-un [:iboga.tag-value/tag
                                       :iboga.tag-value/value])

                ;;TagValues use public fields and are thus not handled by the
                ;;getter/setter mechanisms of iboga
                
                :to-ib #(com.ib.client.TagValue.
                         (str (:iboga.tag-value/tag %))
                         (str (:iboga.tag-value/value %)))

                :from-ib #(hash-map :iboga.tag-value/tag (.-m_tag %)
                                    :iboga.tag-value/value (.-m_value %))}}
   
   #:iboga.tag-value{:tag   {:spec any?}
                     :value {:spec any?}}

   #:iboga.contract
   {:sec-type                          {:spec :iboga.enum/sec-type}
    :last-trade-date-or-contract-month {:spec    date?
                                        :to-ib   format-ib-time
                                        :from-ib parse-ib-time}}
   #:iboga.req.historical-data
   {:end         {:spec  (s/nilable local-date-time?)
                  :to-ib #(when % (format-ib-time %))}
    :duration    {:spec #(re-find #"[0-9]+ [SDWMY]" %)}
    :bar-size    {:spec :iboga.enum/bar-size}
    :show        {:spec :iboga.enum/show}
    :format-date format-date
    :rth?        rth?}
   
   #:iboga.req.head-timestamp
   {:show        {:spec :iboga.enum/show}
    :format-date format-date
    :rth?        rth?}

   #:iboga.recv.historical-data-end
   {:start recv-date-str
    :end   recv-date-str}

   #:iboga.recv.head-timestamp
   {:head-timestamp recv-date-str}

   #:iboga.bar
   {:time {:from-ib parse-ib-time}}

   #:iboga.contract-details
   {:liquid-hours    {:from-ib parse-contract-hours}
    :trading-hours   {:from-ib parse-contract-hours}
    :valid-exchanges {:from-ib #(set (str/split % #","))}
    :order-types     {:from-ib #(set (str/split % #","))}}))
