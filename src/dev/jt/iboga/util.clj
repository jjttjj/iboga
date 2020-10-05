(ns dev.jt.iboga.util
  (:import [java.time Instant LocalDate LocalDateTime ZoneId] 
           [java.io File]
           java.time.format.DateTimeFormatter))

(defn guess-time-zone []
  (let [ini-path (str (or (System/getenv "SystemDrive") ;;windows
                        (System/getProperty "user.home"))
                   File/separator
                   "Jts"
                   File/separator
                   "jts.ini")
        ;;todo, ensure that exists first
        ini      (slurp ini-path)]
    (ZoneId/of (second (re-find #"TimeZone=(.*)" ini)))))

(def ib-datetime-formatter (DateTimeFormatter/ofPattern "yyyyMMdd[  HH:mm:ss]"))


;;needs zdt
(defn format-ib-time [t] (str (.format ib-datetime-formatter t) #_" EST"))
;;(defn format-ib-time [t] (str (.format ib-datetime-formatter t)))

(defn parse-ib-time [x]
  (if (= (count x) 8) ;;breaks in year 10000
    (-> x (LocalDate/parse ib-datetime-formatter))
    (-> x (LocalDateTime/parse ib-datetime-formatter))))

;;https://github.com/weavejester/medley/blob/master/src/medley/core.cljc
(defn take-upto
  "Returns a lazy sequence of successive items from coll up to and including
  the first item for which `(pred item)` returns true. Returns a transducer
  when no collection is provided."
  ([pred]
   (fn [rf]
     (fn
       ([] (rf))
       ([result] (rf result))
       ([result x]
        (let [result (rf result x)]
          (if (pred x)
            (ensure-reduced result)
            result))))))
  ([pred coll]
   (lazy-seq
     (when-let [s (seq coll)]
       (let [x (first s)]
         (cons x (if-not (pred x) (take-upto pred (rest s)))))))))


(defn until-end
  "Returns a lazy sequence of items from coll up to and including the
  first item that has an ::ib/op type whose name ends in 'End'. This
  commonly specifies the last item in a stream of asynchronous replies
  from TWS.

  Returns a transducer when no collection is provided"
  ([]
   (take-upto #(clojure.string/ends-with? (name (:dev.jt.iboga/op %)) "End")))
  ([xs] (into [] (until-end) xs)))
