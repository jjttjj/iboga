(ns iboga.pure
  (:require [clojure.string :as str])
  (:import [java.io BufferedReader DataInputStream DataOutputStream IOException]
           [java.net InetSocketAddress Socket SocketException]
           java.nio.ByteBuffer
           java.nio.charset.StandardCharsets))

(defn socket-open? [socket]
  (not (or (.isClosed socket) (.isInputShutdown socket) (.isOutputShutdown socket))))

(defn socket-read-line-or-nil [socket in]
  (when (socket-open? socket)
    (try (.readLine in)
         (catch SocketException e
           ;;(println e)
           ))))

(defn read-in [^DataInputStream in]
  (let [msg-size (.readInt in)
        ba       (byte-array msg-size)]
    (.read in ba 0 msg-size)
    ba))

(defn socket-read-or-nil [^Socket socket ^DataInputStream in]
  (try (when (socket-open? socket)
         (read-in in))
       (catch IOException e :ignore)))

(defn socket-write [^DataOutputStream out ^bytes x]
  (try
    (.write out x 0 (alength x))
    (.flush out)
    true
    (catch SocketException e
      ;;(println e)
      false)))

(defn close-socket-client [socket]
  (when-not (.isInputShutdown socket)  (.shutdownInput socket))
  (when-not (.isOutputShutdown socket) (.shutdownOutput socket))
  (when-not (.isClosed socket)         (.close socket)))

(defn ib-val->str [x]
  (cond
    (string? x)  x
    (boolean? x) (if x "1" "0")
    :else        (str x)))

(defn msg->bytes [coll]
  (let [sep     (char 0) ;;== (Character/MIN_VALUE)
        msg-str (str (str/join sep (map ib-val->str coll)) sep)
        ba      (.getBytes msg-str StandardCharsets/UTF_8)
        len     (alength ba)
        bb      (ByteBuffer/allocate (+ len 4))] ;;4==(Integer/BYTES)
    (-> bb (.putInt len) (.put ba) .array)))

(defn init-bytes []
  (let [ver-str "v100..150"
        pre     (.getBytes "API\0" StandardCharsets/UTF_8)
        ver     (.getBytes ver-str  StandardCharsets/UTF_8)
        len     (alength ver)
        bb      (ByteBuffer/allocate (+ len (alength pre) 4))] ;;(Integer/BYTES)
    (-> bb (.put pre) (.putInt len) (.put ver) .array)))

(defn bytes->strs [^bytes ba]
  (-> ba (String. "UTF-8") (str/split #"\x00")))

(defn socket [address port f]
  (let [address         (InetSocketAddress. address port)
        socket          (doto (Socket.) (.connect address))
        in              (DataInputStream.  (.getInputStream socket))
        out             (DataOutputStream. (.getOutputStream socket))
        server-version  (promise)
        connection-time (promise)]

    (socket-write out (init-bytes))
    
    (let [[ver tm] (bytes->strs (socket-read-or-nil socket in))]
      (deliver server-version (Integer/parseInt ver))
      ;;not sure if it's worth it to parse the time string here:
      (deliver connection-time tm))
    
    (future (while (socket-open? socket)
              (when-let [x (socket-read-or-nil socket in)]
                (f (bytes->strs x))))
            ;;(println "closing socket")
            )

    {:server-version  @server-version
     :connection-time @connection-time
     :close-fn        #(close-socket-client socket)
     :send-fn         (fn [x]
                        (if (socket-open? socket) ;;deal with nil/false
                          (socket-write out (msg->bytes x))
                          (close-socket-client socket)))
     :socket          socket}))
