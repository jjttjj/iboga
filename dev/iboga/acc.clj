(ns iboga.acc)

(defn mkput [a p rf cbs]
  (fn [x]
    (locking p
      (if (realized? p)
        false
        (let [result (rf a x)]
          (if (reduced? result)
            (let [x @@result]
              ;;run callbacks then deliver
              (doseq [cb @cbs] (cb x))
              (deliver p x))
            result)
          true)))))

(defn acc
  "Accumulates state in an atom subject to a transducer
  Returns a map with the keys :put!, :register-cb, :a and :p.
  :put! is a function which adds its single argument to atom with rf
  subject to xf.
  :p is a promise which will be delivered the state in :a when rf
  results in a 'reduced'. Before p is realized, all callbacks will be
  called with the final value in :a"
  ([xf rf] (acc xf rf (rf)))
  ([xf rf init]
   (let [a       (atom init)
         swapper (fn [acc x] (swap! acc rf x) acc)
         rf      (xf swapper)
         p       (promise)
         cbs     (atom [])]
     {:a           a
      :p           p
      :put!        (mkput a p rf cbs)
      :register-cb (fn [f]
                     (assert (not (realized? p)) "Promise is already realized")
                     (swap! cbs conj f))})))

(defn put!
  "put! a value into an `acc` subject to its transduer. Returns false if
  the acc is already complete and not accepting new values, otherwise
  returns true"
  [this x]
  ((:put! this) x))

(defn on-realized [this f]
  ((:register-cb this) f))

(defrecord AccProm [a p register-cb put!]
  clojure.lang.IDeref
  (deref [this]
    (deref p))
  clojure.lang.IBlockingDeref
  (deref [this timeout-ms timeout-val]
    (deref p timeout-ms timeout-val)))

(defn acc-prom
  ([xf rf] (acc-prom xf rf (rf)))
  ([xf rf init]
   (map->AccProm (acc xf rf init))))

(defrecord AccAtom [a p register-cb put!]
  clojure.lang.IDeref
  (deref [this]
    (deref a)))

(defn acc-atom
  ([xf rf] (acc-prom xf rf (rf)))
  ([xf rf init]
   (map->AccAtom (acc xf rf init))))

;;todo: consider filling out protocols, see clojure.core/promise, atom
