# IBoga

Iboga is a data driven clojure wrapper around Interactive Brokers' Java api client.

Iboga is currently very experimental and will be subject to frequent breaking changes.


## Installation

Iboga requires that the the Java tws-api client be compiled with the javac `--parameters` flag to enable access to parameter names via reflection. The following tool does the installation for you in a single command.

https://github.com/jjttjj/tws-install

## Usage

```clojure
(require '[dev.jt.iboga :as ib])
```

`ib/client`: takes a map with keys `::ib/host`, `::ib/port`, and `::ib/client-id`. `(ib/dflt-config)` will generate give you config for a paper money connection with a random client-id.

```clojure
(def c1 (ib/client (ib/dflt-config)))
```

`ib/on` attaches a handler to the connection. We can pass it an options map containing `:xf` and/or `:buf` to set a transducer to pass all messages through, or a buffer for the messages.

```clojure
(ib/on c1 #(println "Error: " (or (:errorMsg %) %)) {:xf (ib/match-op :error)})
(def log (atom []))
(ib/on c1 #(swap! log conj %))
```

`ib/tap` takes a client and a channel and returns a copy of all IB messages on the channel. You can use a transducer on the channel to limit/transform messages.

```clojure
(require '[clojure.core.async :as a])

(ib/tap c1 (a/chan (a/sliding-buffer 1000)
                   (filter (fn [msg] (= (:reqId msg) 123)))))
```

To send a message to the IB client use `ib/send!`. 

```clojure
(ib/send! c1 {::ib/op :reqCurrentTime})
(print (last @log))
{:dev.jt.iboga/op :currentTime,
 :time 1601305364,
 :dev.jt.iboga/ts
 #object[java.time.Instant 0x3701acee "2020-09-28T15:02:42.342586200Z"],
 :dev.jt.iboga/client-id 247309670}
 ```

All messages sent to and received from IB will be a map which has an `::ib/op` key which corresponds to the name of the EClient/EWrapper method names. It will also contain an unqualified keyword+value pair for each parameter, with the keyword corresponding to the name used in the Java Client source. See [EWrapper](http://interactivebrokers.github.io/tws-api/interfaceIBApi_1_1EWrapper.html) for received messages and [EClient](http://interactivebrokers.github.io/tws-api/classIBApi_1_1EClient.html) for send messages.
You can use `ib/req->params` and `ib/recv->params` to look up parameter names for a given method.

```clojure
(ib/req->params :reqHistoricalData)
;;=>
[:tickerId
 :contract
 :endDateTime
 :durationStr
 :barSizeSetting
 :whatToShow
 :useRTH
 :formatDate
 :keepUpToDate
 :chartOptions]
```

IB data types such as `Contract` and `Order` can be specified as maps with keywords which correspond to getters and setters, which additionall need an `::ib/type` key with a value corresponding to the non-qualified class name, such as :Contract or :Order.

```clojure
{:symbol      sym
 :secType     "STK"
 :localSymbol "SPY"
 :currency    "USD"
 :exchange    "SMART"
 ::ib/type    :Contract}
```


For more, see [examples](examples/examples1.clj).

## License

Copyright © 2020 Justin Tirrell

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version. https://opensource.org/licenses/EPL-1.0
