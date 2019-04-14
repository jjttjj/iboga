(ns iboga.async
  (:require [clojure.core.async :as a])
  (:import [com.ib.client EClient EClientSocket EJavaSignal EReader EWrapper]
           [java.time LocalDate LocalDateTime LocalTime]
           java.time.format.DateTimeFormatter))


;;EWrapper;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def method-data
  (for [method (.getDeclaredMethods EWrapper)]
    [(.getName method)
     (for [param (.getParameters method)]
       [(.getType param) (.getName param)])]))

(defn make-reify-specs [cb kfn]
  (for [[method-name params] method-data]
    `(~(with-meta (symbol method-name) {:tag 'void})
      [this#
       ~@(map
          (fn [[ptype pname]]
            
            (with-meta (symbol pname)
              {:tag (symbol (pr-str ptype))}))
          params)]
      (~cb
       (-> (hash-map
            ~@(mapcat (fn [[ptype pname]]
                        [`(~kfn ~pname) (symbol pname)])
                      params))
           (assoc :type (~kfn ~method-name)))))))


(defmacro wrapper [cb & [opts]]
  (let [{:keys [kfn] :or {kfn keyword}} opts]
    `(reify com.ib.client.EWrapper
       ~@(make-reify-specs cb kfn))))

;;EClientSocket;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn process-messages [client reader signal]
  (while (.isConnected client)
    (.waitForSignal signal)
    (try
      (.processMsgs reader)
      (catch Throwable t
        (println "Exception" t)))))

(defn connect
  ([wr host port client-id]
   (let [sig    (EJavaSignal.)
         client (EClientSocket. wr sig)
         _      (.eConnect client host port
                           client-id)
         reader (EReader. client sig)]
     
     (.start reader)
     (future (process-messages client reader sig))
     client)))

;;Async Connection;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;temporary fix to allow large historical data requests
;;This would ideally be a much lower default and configurable per request.
(def default-buf-size 12048) 

(defn handle-msgs
  ([ch] (handle-msgs ch nil))
  ([ch handler]
   (a/go-loop []
     (if-let [msg (a/<! ch)]
       (do
         (when handler (handler msg))
         (recur))
       ;;(println "channel closed")
       ))))

(defn async-conn
  ([] (async-conn "127.0.0.1" 7496 (rand-int (Integer/MAX_VALUE))))
  ([address port client-id] (async-conn address port client-id nil))
  ([address port client-id default-handler]
   (let [in-ch   (a/chan default-buf-size)
         in-mult (a/mult in-ch)
         tap-fn  #(a/tap in-mult %)
         _       (handle-msgs (tap-fn (a/chan default-buf-size)) default-handler)
         sock    (connect (wrapper #(a/put! in-ch %)) address port client-id)]

     {:client-socket sock
      :tap-fn        tap-fn})))

;;Requests;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def req-data
  (->>
   (for [method (filter #(re-find #"public synchronized void" (str %))
                        (.getDeclaredMethods EClient))]

     [(keyword (.getName method))
      (mapv (comp keyword (memfn getName)) (.getParameters method))])
   (into {})))

(defn arglist->argmap [req-type arglist]
  (zipmap (req-data req-type) arglist))

(defn req! [conn req-type args mkxf] ;;handlers
  (let [{:keys [client-socket tap-fn]} conn
        arglist                        (if (map? args) (map args (req-data req-type)) args)
        argmap                         (if (map? args) args (arglist->argmap req-type args))
        ch                             (a/chan default-buf-size (mkxf argmap))]
    (tap-fn ch)
    (clojure.lang.Reflector/invokeInstanceMethod client-socket
                                                 (name req-type)
                                                 (to-array arglist))

    (handle-msgs ch)))

;;for requests that do not expect or do anything with a response
(defn do! [conn args req-type]
  (req! conn req-type args (fn [_] (take 0)))
  nil)

;;General Util;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;From https://github.com/weavejester/medley/blob/1.1.0/src/medley/core.cljc#L263

(defn take-upto
  "Returns a lazy sequence of successive items from coll up to and including
  the first item for which `(pred item)` returns true."
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

;;Gateway;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ib-datetime-formatter (DateTimeFormatter/ofPattern "yyyyMMdd[  HH:mm:ss]"))

(defn parse-time [s]
  (if (= (count s) 8)
    (-> s (LocalDate/parse ib-datetime-formatter) (LocalDateTime/of LocalTime/MIDNIGHT))
    (-> s (LocalDateTime/parse ib-datetime-formatter))))

(defn msg-id [msg] (or (:reqId msg) (:tickerId msg) (:id msg)))

(defn rand-id [] (rand-int (Integer/MAX_VALUE)))

(defn bool->bit [bool] (case bool true 1 false 0))

(defn ids-match-xf [argmap]
  (filter #(= (msg-id argmap) (msg-id %))))

(defn request-historical-data [conn req-id contract end-date duration
                               bar-size what-to-show use-rth? format-date
                               update?
                               {:keys          [data error end update-end]
                                update-handler :update}]
  (let [mkbar       (fn [bar]
                      {:open  (.open bar)  :high   (.high bar)   :low   (.low bar)
                       :close (.close bar) :volume (.volume bar) :count (.count bar)
                       :wap   (.wap bar)
                       :time  (-> (.time bar) parse-time)})
        ->ib        {:endDateTime #(.format % ib-datetime-formatter)
                     :useRTH      bool->bit}
        update-end? (fn [{:keys [type errorCode errorMsg] :as msg}]
                      (and (= type :error) (= errorCode 162)
                           (when (re-find #"API historical data query cancelled" errorMsg)
                             true)))
        cb          #(condp = (:type %)
                       :historicalData       (when data (data (:bar %)))
                       :historicalDataUpdate (when update-handler (update-handler (:bar %)))
                       :error                (if (and update? (update-end? %))
                                               (when update-end (update-end %))
                                               (when error (error %)))
                       :historicalDataEnd    (when end (end %)))
        finished?   (if update?
                      update-end?
                      (comp #{:historicalDataEnd :error} :type))]
    (req! conn :reqHistoricalData
          [req-id
           contract
           (when end-date ((->ib :endDateTime) end-date))
           duration
           bar-size
           what-to-show
           ((->ib :useRTH) use-rth?) ;;use rth
           format-date ;;"2" here can make it an int.
           update? 
           nil ;;unused extra arg
           ]
          (fn [argmap]
            (comp
             (ids-match-xf argmap)
             (map #(if (:bar %) (update % :bar mkbar) %))
             (take-upto finished?)
             (map #(do (cb %) %)))))))

(defn cancel-historical-data [conn req-id]
  (do! conn [req-id] :cancelHistoricalData))


(defn request-matching-symbols [conn req-id pattern {:keys [data]}]
  (let [cb #(condp = (:type %)
              :symbolSamples (when data (data (:contractDescriptions %))))]
    (req! conn :reqMatchingSymbols [req-id pattern]
          (fn [argmap]
            (comp
             (filter #(= (msg-id argmap) (msg-id %)))
             (map #(do (cb %) %)))))))
