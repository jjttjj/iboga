(ns dev.jt.iboga
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.java.data :as jd]
            [dev.jt.iboga.util :as u]
            [clojure.core.async :as a :refer [<! <!! >!! >! go go-loop chan put!
                                              alt! alts! alt!! alts!!
                                              poll! offer! take! put!]])
  (:import [java.lang.reflect Modifier Method Parameter Type ParameterizedType]
           [com.ib.client Types Contract Order TagValue EClient EWrapper EDecoder
            EJavaSignal EReader EClientSocket]
           java.time.Instant))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Data

(defn get-first-actual-when-parameterized [^Parameter p]
  (let [^ParameterizedType pt (.getParameterizedType p)]
    (when (instance? ParameterizedType pt)
      (let [actual (.getActualTypeArguments pt)]
        (when-not (= 1 (count actual))
          (println "Warning: more than one getActualtypeArguments for"
            pt " actual: " actual))
        (first actual)))))

(defn- make-setter-fn [^Method method]
  (let [^Parameter p (first (.getParameters method))
        ^Type t      (.getType p)
        ^Type actual (get-first-actual-when-parameterized p)]
    (fn [instance value]
      (.invoke method instance
        (into-array [(jd/to-java t
                       (if actual
                         (map (partial jd/to-java actual) value)
                         ;;what if there are multiple actuals?
                         value))])))))

;;if there is an enum setter and a string setter of the same name we need to
;;pick the string one because in some cases there is not an enum value for all
;;valid values.
(defn probably-setter? [^Method m]
  (boolean
    (and (= Modifier/PUBLIC (.getModifiers m))
      (= 1 (.getParameterCount m))
      (not (#{"equals"} (.getName m)))
      (= "void" (str (.getReturnType m))))))

(defn setter-fns [^Class clazz]
  (->> (.getDeclaredMethods clazz)
       (filter probably-setter?)
       (group-by (memfn getName))
       (reduce (fn [acc [nm setters]]
                 (let [method (if (= (count setters))
                                (first setters)
                                (do ;;(assert (= 2 (count setters)))
                                  (->> setters
                                       (filter (fn [^Method setter]
                                                 (= String (first (.getParameterTypes setter)))))
                                       first)))]
                   
                   (assoc acc (keyword nm) (make-setter-fn method))))
         {})))

(defn- set-properties-on
  "Given an instance, its class, and a hash map of properties,
  call the appropriate setters and return the populated object.
  Used by to-java and set-properties below."
  [instance ^Class clazz props]
  (let [setter-map (setter-fns clazz)]
    (doseq [[key value] props]
      (let [setter (get setter-map (keyword key))]
        (if (nil? setter)
          (#'jd/throw-log-or-ignore-missing-setter key clazz)
          (apply setter [instance value]))))
    instance))

(def data-classes
  #{com.ib.client.Contract
    com.ib.client.ContractDescription
    com.ib.client.ContractDetails
    com.ib.client.CommissionReport
    com.ib.client.Execution
    com.ib.client.PriceIncrement
    com.ib.client.Order
    com.ib.client.ComboLeg
    com.ib.client.OrderState
    com.ib.client.SoftDollarTier
    com.ib.client.NewsProvider
    com.ib.client.DeltaNeutralContract
    
    com.ib.client.Bar
    com.ib.client.ScannerSubscription})

(doseq [clazz data-classes]
  (derive clazz ::data-class))


(defmethod jd/to-java [java.util.List clojure.lang.Sequential]
  [destination-type xs]
  (java.util.ArrayList. xs))

(defmethod jd/to-java [::data-class clojure.lang.APersistentMap] [^Class clazz props]
  (if (.isInterface clazz)
    (if (instance? clazz props)
      (condp = clazz
        ;; make a fresh (mutable) hash map from the Clojure map
        java.util.Map (java.util.HashMap. ^java.util.Map props)
        ;; Iterable, Serializable, Runnable, Callable
        ;; we should probably figure out actual objects to create...
        props)
      (throw (IllegalArgumentException.
               (str (.getName clazz) " is an interface "
                 "and cannot be constructed from "
                 (str/join ", " (map name (keys props)))))))
    (let [ctr-args (::constructor (meta props))
          ctr      (when ctr-args (#'jd/find-constructor clazz ctr-args))
          instance (try
                     (if ctr
                       (.newInstance ctr (object-array ctr-args))
                       (.newInstance clazz))
                     (catch Throwable t
                       (throw (IllegalArgumentException.
                                (str (.getName clazz)
                                  " cannot be constructed")
                                t))))]
      (set-properties-on instance clazz props))))

(prefer-method jd/to-java
  [::data-class clojure.lang.APersistentMap]
  [Object clojure.lang.APersistentMap])


(defn is-getter [^Method method]
  (and method
    (= 0 (alength ^"[Ljava.lang.Class;" (.getParameterTypes method)))
    (not (#{"clone" "toString" "hashCode"} (.getName method)))
    (not= "void" (str (.getReturnType method)))))

(defn- add-getter-fn [the-map ^Method method]
  (let [name (.getName method)]
    (if (and (is-getter method) (not (= "class" name))) ;;this might be incorrect remnant from jd:
      (assoc the-map (keyword name) (#'jd/make-getter-fn method))
      the-map)))

(defmethod jd/from-java :default [^Object instance]
  (let [clazz (.getClass instance)]
    (if (.isArray clazz)
      ((:from (#'jd/add-array-methods clazz)) instance)
      (let [getter-map (reduce add-getter-fn {} (.getDeclaredMethods clazz))]
        ;;getter-map
        (cond->
            (into {}
              (for [[key getter-fn] (seq getter-map)
                    :let            [v (getter-fn instance)]
                    :when v]
                [key v]))
          (data-classes clazz)
          (assoc ::type (keyword (.getSimpleName clazz))))))))

(defmethod jd/to-java [TagValue clojure.lang.APersistentMap] [^Class clazz props]
  (TagValue. (str (:tag props)) (str (:value props))))

(defmethod jd/from-java TagValue [instance]
  {:tag (.-m_tag instance) :value (.-m_value instance)})

(defn get-type [x]
  (or (::type x) (::type (meta x))))

(defn to-ib
  ([x]
   (let [clazz-key (get-type x)]
     (assert clazz-key)
     (to-ib clazz-key x)))
  ([clazz-key x]
   (jd/to-java (Class/forName (str "com.ib.client." (name clazz-key)))
     x)))

(defn from-ib [x]
  (jd/from-java x))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Req / Recv

(defn method+param-keys [method]
  [(keyword (.getName method)) (mapv #(keyword (.getName %)) (.getParameters method))])

;;broken for error
(def recv->params
  (->> (.getDeclaredMethods EWrapper)
       (map method+param-keys)
       (into {})))

(def req->params
  (->> (.getDeclaredMethods EClient)
       (filter #(= (+ Modifier/PUBLIC Modifier/SYNCHRONIZED) (.getModifiers %)))
       (map method+param-keys)
       (into {})))

(def op->params (merge recv->params req->params))

(def id-keys #{:reqId :requestId :orderId :tickerId :id})

(def op->id-key
  (into {}
    (for [[op params] op->params
          :let        [id-key (some id-keys params)]
          :when       id-key]
      [op id-key])))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Client

(def ewrapper-signatures
  (->> (.getDeclaredMethods EWrapper)
       (map (fn [method]
              (list (with-meta (symbol (.getName method)) {:tag "void"})
                (mapv (fn [tag pname]
                        (with-meta pname
                          {:tag tag}))
                  (map pr-str
                    (.getParameterTypes method))
                  (map (comp symbol (memfn getName)) (.getParameters method))))))))


(defn default-recv-template [sig handler]
  (let [[msym tagged-params] sig
        pkeys                (mapv keyword tagged-params)
        untagged             (map #(with-meta % nil) tagged-params)
        payload              (-> (apply hash-map
                      ::op (keyword msym)
                      (interleave pkeys untagged))
                    (with-meta
                      {::params pkeys}))]
    (list msym (vec (cons 'this tagged-params))
      (list handler payload))))

(defn make-reify-specs [handler & [{:keys [recv-template-fn] :as opt
                                    :or   {recv-template-fn default-recv-template}}]]
  (->> ewrapper-signatures (map (fn [sig] (recv-template-fn sig handler)))))

(defmacro handler-ewrapper [handler & [opt]]
  `(reify com.ib.client.EWrapper ~@(make-reify-specs handler opt)))

;;https://github.com/InteractiveBrokers/tws-api/blob/master/source/javaclient/com/ib/client/EJavaSignal.java
(defn process-messages [client reader signal]
  (while (.isConnected client)
    (.waitForSignal signal)
    (.processMsgs reader)))

(defn connect [{::keys [eclient-socket ereader-signal]} host port client-id]
  {:pre [eclient-socket host port client-id]}
  (.eConnect eclient-socket host port client-id)
  (let [ereader (EReader. eclient-socket ereader-signal)]
    (.start ereader)
    (future (process-messages eclient-socket ereader ereader-signal))))

(defn connected? [{::keys [eclient-socket]}]
  (.isConnected eclient-socket))

;;todo: catch socket close EOF exception?
(defn disconnect [{::keys [eclient-socket]}]
  {:pre [eclient-socket]}
  (.eDisconnect eclient-socket))

(defn -invoke [obj mname args]
  (clojure.lang.Reflector/invokeInstanceMethod obj (name mname) (to-array args)))

(defn arg->ib [x]
  (cond-> x (get-type x) to-ib))

(defn msg->clj [m]
  (reduce
    (fn [acc k]
      (update acc k from-ib))
    m
    (::params (meta m))))

(defn socket+signal [handler]
  (let [wrapper (handler-ewrapper handler)
        sig     (EJavaSignal.)
        ecs     (EClientSocket. wrapper sig)]
    {::eclient-socket ecs
     ::ereader-signal sig}))

(defn -send! [conn req]
  (-invoke (::eclient-socket conn)
    (name (::op req))
    (map arg->ib (map req (req->params (::op req))))))

(defn send! [conn req]
  {:pre [(map? req) (map? conn) (::op req)]}
  ;;(validate-req req)
  (-send! conn req))

(defn -client [{::keys [host port client-id handler ->clj key-fn]
                :as    conf
                :or    {->clj true}}]
  {:pre [host port client-id handler]}
  (let [c (socket+signal (if ->clj (comp handler msg->clj) handler))]
    (when-let [opts (::connect-options conf)]
      (.setConnectOptions (::eclient-socket c) opts))
    (merge conf c)))

(def dflt-paper-port 7497)

(defn dflt-config []
  {::host      "localhost"
   ::port      dflt-paper-port
   ::client-id (rand-int (Integer/MAX_VALUE))})

;;use spec?
#_
(defn validate-req [req]
  (assert (every? some? (map req (data/req->params (::op req))))
    (str "missing args for req. needs: "
      (req->params (::op req)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Async client
(defn client [{::keys [host port client-id ] :as ctx
               :keys  [::buf ::out]}]
  {:pre [host port client-id]}
  (let [out  (or out (chan (or buf 10)))
        ib   (-> ctx
                 (assoc ::handler (fn [msg] (a/put! out
                                              (assoc msg
                                                ::ts (Instant/now)
                                                ::client-id client-id))))
                 -client)
        mult (a/mult out)]
    (-> ib
        (assoc
          ::recv-mult mult
          ::start-fn (fn client-start! []
                       (connect ib host port client-id))
          ::stop-fn (fn client-stop! [] (disconnect ib))))))

(defn tap [{::keys [recv-mult]} ch]
  {:pre [recv-mult]}
  (a/tap recv-mult ch))

(defn on [client f & [{:keys [xf buf]}]]
  (let [buf (or buf (a/sliding-buffer 2048))
        ch  (if xf (chan buf xf) (chan buf))]
    (go-loop []
      (when-some [x (<! ch)]
        (f x)
        (recur)))
    (tap client ch)
    #(a/close! ch)))

(defn start [{::keys [start-fn] :as ctx}]
  {:pre [start-fn]}
  (start-fn)
  ctx)

(defn stop [{::keys [stop-fn] :as ctx}]
  {:pre [stop-fn]}
  (stop-fn)
  ctx)

;;; Stream helpers

(def id-keys #{:reqId :requestId :orderId :tickerId :id})

(defn get-id [msg]
  (first (keep msg id-keys)))

(defn match-ids
  ([id]
   (filter (fn [msg] ((if (set? id) id #{id}) (get-id msg)))))
  ([id xs] (into [] (match-ids id) xs)))

(defn match-kv
  ([k v] (filter (fn [x] (= (get x k) v))))
  ([k v xs] (into [] (match-kv k v) xs)))

(defn match-op
  ([op]
   (filter (comp (if (set? op) op #{op}) ::op)))
  ([op xs] (into [] (match-op op) xs)))

(defn end-at
  ([op]
   (u/take-upto (comp (if (set? op) op #{op}) ::op)))
  ([op xs] (into [] (end-at op) xs)))


;;; Req transducers

;;; Should end messages be included in the response?

;;Note: match-ids will allow error messages, which just have an :id key, to
;;match relevant messages

(def req-op->end-op
  (->> (keys recv->params)
       (keep (fn [k]
               (re-find #"(.+)End$" (name k))))
       (keep (fn [[end subj]]
               (when-let [reqs (->> (keys req->params)
                                    (filter (fn [k]
                                              (and
                                                (= (str/lower-case (str "req" subj))
                                                  (str/lower-case (name k)))
                                                )))
                                    not-empty)]
                 (assert (= (count reqs) 1))
                 [(first reqs) (keyword end)])))
       (into {})))

(def req-op->cancel-op
  (->> req->params
       keys
       (keep (fn [k] (re-find #"cancel(.*)$" (name k))))
       (keep (fn [[canc subj]]
               (when-let [reqs (->> (keys req->params)
                                    (filter (fn [k] (= (name k) (str "req" subj))))
                                    not-empty)]
                 (assert (= (count reqs) 1))
                 [(first reqs) (keyword canc)])))
       (into {})))

(def req-op->id-key
  (->>
    (for [[k v] req->params
          :let  [id-key (some id-keys v)]
          :when id-key]
      [k id-key])
    (into {})))

(def req-op->response-op
  {:reqManagedAccts :managedAccounts
   :reqIds          :nextValidId})

(def single-response-ops
  #{:reqManagedAccts
    :nextValidId})

;;; Cancellation messages

(defmulti cancel-req (fn [req] (::op req)) :default ::default)

(defmethod cancel-req ::default [{::keys [op] :as req}]
  (when-let [canc-op (req-op->cancel-op op)]
    (let [idk (req-op->id-key op)]
      {::op canc-op
       idk  (get req idk)})))

(defmethod cancel-req :reqMktDepth [req]
  {::op          :cancelMktDepth
   :tickerId     (:tickerId req)
   :isSmartDepth (:isSmartDepth req)})

(defmethod cancel-req :reqAccountUpdates [req]
  (assoc req :subscribe false))

(defmulti response-xf (fn [req] (::op req)) :default ::default)

(defmethod response-xf ::default [req] nil)

(defmethod response-xf :reqContractDetails [{:keys [reqId] :as req}]
  (comp (match-ids reqId)
        (end-at :contractDetailsEnd)
        (match-op :contractDetails)))

(defmethod response-xf :reqPositions [req]
  (comp (end-at :positionEnd)))

(defmethod response-xf :reqManagedAccts [req]
  (comp (match-op :managedAccounts) (take 1)))

(defmethod response-xf :reqIds [req]
  (comp (match-op :nextValidId) (take 1)))

;;; Todo: special case historical data when keepUpToDate is true
(defmethod response-xf :reqHistoricalData [req]
  (comp (match-ids (get-id req))
        (end-at :historicalDataEnd)
        (match-op :historicalData)))

(defn req! [ib {::keys [op buf xf ch post-xf] :as req}]
  (let [xf      (cond-> (or xf (response-xf req))
                  post-xf (comp post-xf))
        buf     (or buf (a/sliding-buffer 2048))
        ch      (cond ch    ch
                      xf    (chan buf xf)
                      :else (chan buf))
        canc-fn (when-let [canc-msg (cancel-req req)]
                  #(send! ib canc-msg))]
    (tap ib ch)
    (send! ib req)
    (cond-> {::ch ch ::req req}
      canc-fn (assoc ::cancel-fn canc-fn))))

(defn cancel! [response]
  (when-let [cancf (::cancel-fn response)]
    (cancf)))

(defn result+ [ch & {:keys [timeout]
                     :or   {timeout 2000}}]

  (let [ch (a/into [] ch)
        to (a/timeout timeout)]
    (a/alt!!
      ch ([x] x)
      to ([_] ::timeout))))

(defn timeout? [x]
  (identical? ::timeout x))

(defn sync! [{::keys [ch] :as resp} & {:keys [timeout]
                                       :or   {timeout 2000}}]
  (result+ ch :timeout timeout))
