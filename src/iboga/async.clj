(ns iboga.async
  (:require [clojure.core.async :as a])
  (:import [com.ib.client EClient EClientSocket EJavaSignal EReader EWrapper]))


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

(def default-buf-size 12048)

(defn handle-msgs
  ([ch] (handle-msgs ch nil))
  ([ch handler]
   (a/go-loop []
     (if-let [msg (a/<! ch)]
       (do
         (when handler (handler msg))
         (recur))
       (println "channel closed")))))

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
