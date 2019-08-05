(ns iboga.wip
  (:require [clojure.set :as set]
            [iboga.core :refer :all])
  (:import [com.ib.client Types Types$BarSize Types$WhatToShow]))

(def ^:dynamic *timeout* 2000)

(defn add-id [args id]
  (if (vector? args)
    (into [id] args)
    (assoc args :id id)))

(defn current-time [conn]
  (let [result (promise)
        f (fn this [[msg-key msg]]
            (when (= msg-key :current-time)
              (deliver result (:time msg))
              (remove-handler conn this)))]
    (add-handler conn f)
    (req conn [:current-time []])
    @result))

(defn next-order-id [conn]
  (let [result (promise)
        f      (fn this [[msg-key msg]]
                 (when (= msg-key :next-valid-id)
                   (deliver result (:order-id msg))
                   (remove-handler conn this)))]
    (add-handler conn f)
    (req conn [:ids [0]]);;arg for :ids does nothing
    @result))

(defn historical-data [conn args]
  (let [result (promise)
        error  (promise)
        bars   (atom [])
        id     (next-id conn)
        args   (add-id args id)
        f      (fn this [[msg-key {:keys [bar] :as msg}]]
                 (when (= (:id msg) id)
                   (cond
                     bar
                     (swap! bars conj bar)
                     
                     (= msg-key :historical-data-end)
                     (do (deliver result @bars) (remove-handler conn this))

                     (= msg-key :error)
                     (do (deliver result ::error)
                         (deliver error msg))

                     ;;else: unrecognized message?
                     )))]
    (add-handler conn f)
    (req conn [:historical-data args])
    (condp = (deref result 60000 ::timeout)
      ::error   (do (req conn [:cancel-historical-data [id]])
                    (throw (ex-info "Historical Data Error" @error)))
      ::timeout (throw (Exception. "historical data timeout"))
      @result)))

(defn head-timestamp [conn args]
  (let [result (promise)
        error  (promise)
        id     (next-id conn)
        args   (add-id args id)
        f      (fn this [[msg-key msg]]
                 (when (= (:id msg) id)
                   (cond
                     (= msg-key :head-timestamp)
                     (do (deliver result (:head-timestamp msg))
                         (remove-handler conn this)
                         (req conn [:cancel-head-timestamp [id]]))

                     (= msg-key :error)
                     (do (deliver result ::error)
                         (deliver error msg)))))]
    (add-handler conn f)
    (req conn [:head-timestamp args])
    (condp = (deref result *timeout* ::timeout)
      ::error   (do (req conn [:cancel-head-timestamp [id]])
                    (throw (ex-info "head timestamp Error" @error)))
      ::timeout (throw (Exception. "head-timestamp timeout"))
      @result)))

(defn contract-details [conn args]
  (let [result (promise)
        error  (promise)
        acc   (atom [])
        id     (next-id conn)
        args   (add-id args id)
        f      (fn this [[msg-key msg]]
                 (when (= (:id msg) id)
                   (cond
                     (= msg-key :contract-details)
                     (swap! acc conj (:contract-details msg))
                     
                     (= msg-key :contract-details-end)
                     (do (deliver result @acc) (remove-handler conn this))

                     (= msg-key :error)
                     (do (deliver result ::error)
                         (deliver error msg))

                     ;;else: unrecognized message?
                     )))]
    (add-handler conn f)
    (req conn [:contract-details args])
    (condp = (deref result 10000 ::timeout)
      ::error   (throw (ex-info "Contract Details Error" @error))
      ::timeout (throw (Exception. "contract details timeout"))
      @result)))

(defn mkt-data [conn args f]
  (let [acc     (atom [])
        error   (atom [])
        id      (next-id conn)
        args    (add-id args id)
        h       (fn this [msg]
                  (when (= (:id msg) id)
                    (if (= (:iboga.event/type msg) :error)
                      ::error ;;todo: handle errors

                      (f msg))))
        stop-fn (fn []
                  (req conn [:cancel-mkt-data [id]])
                  (remove-handler conn h)
                  nil)]
    
    (add-handler conn h)
    (req conn [:mkt-data args])
    stop-fn))
