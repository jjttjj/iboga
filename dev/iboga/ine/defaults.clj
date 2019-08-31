(ns iboga.ine.defaults
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [iboga.core :as iboga]
            [iboga.meta :as meta])
  (:import [java.time LocalDate LocalDateTime]
           java.time.format.DateTimeFormatter))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;request defaults;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn default-duration [bar-size]
  (if (#{"1 day" "1 week" "1 month"} bar-size)
    "1 Y"
    "1 D"))

(def defaults-data
  {:historical-data {:default-vals {:show          "TRADES"
                                    :chart-options []
                                    :bar-size      "1 day"
                                    :format-date   1
                                    :rth?          true
                                    :update?       false}
                     :default-fn   (fn [argmap]
                                     (cond-> {}
                                       (and (not (contains? argmap :end))
                                            (not (:update? argmap)))
                                       (assoc :end (LocalDateTime/now))
                                       
                                       (not (contains? argmap :duration))
                                       (assoc :duration (default-duration (:bar-size argmap)))
                                       
                                       true (merge argmap)))}
   :head-timestamp {:default-vals {:format-date 1
                                   :show        "TRADES"
                                   :rth?        true}}
   :ids            {:default-vals {:num-ids 1}}})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;defaults;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn add-defaults [req-key argmap]
  (let [dflt-vals (get-in defaults-data [req-key :default-vals] {})
        dflt-fn   (get-in defaults-data [req-key :default-fn])]
    (cond-> (merge dflt-vals argmap)
      dflt-fn dflt-fn)))

(def req-args-opt-un
  (for [k (keys defaults-data)
        :let [opt (set (keys (add-defaults k {})))
              qk (iboga/req-spec-key k)
              qual-args (meta/req-key->field-keys qk)]]
    [qk (group-by #(if (contains? opt (iboga/unqualify %))
                     :opt
                     :req) qual-args)]))

(defn def-req-specs []
  (doseq [[req-spec-key v] req-args-opt-un]
    (let [{:keys [req opt]} v]
      (eval `(s/def ~req-spec-key
               (s/keys ~@(when (not-empty req) [:req-un req])
                       ~@(when (not-empty opt) [:opt-un opt])))))))

(defn add-default-args [ctx]
  (update ctx :args #(add-defaults (:req-key ctx) %)))
