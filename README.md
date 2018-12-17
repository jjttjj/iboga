# iboga

A minimum viable clojure client for Interactive Brokers's API.

Iboga is an experiemental work in progress.

Allows socket connections to Interactive Broker's Trader Workstation software without depending on their client APIs. Iboga is very small, currently under 100 lines of code (mostly just dealing with managing the socket connection) with no external dependencies.

The end user of this library is required to figure out the very complicated messaging scheme IB uses internally. This will probably involve digging into the official client's source code.

You can get the official IB client source code [here](http://interactivebrokers.github.io/). The Mac/Unix version includes the source code. One advantage of the approach used in Iboga is that it should work with the most recent version as long as you figure out the messaging scheme for the incoming and outgoing messages you'll be working with.

I went down a rabbit hole of trying to make this library more user friendly and present a clojure friendly high level interface, including things like trying reflection and parsing the java source, but ultimatly the versioning scheme IB uses is pretty complex and I gave up when I realized there's not really a way around just doing a 1:1 translation of the Java client into clojure. I don't think managing a project that size is worth what is gained from getting rid of the java client. If someone else is able to figure this out though I am more than happy to take suggestions/PRs!

I think the light approach iboga uses might be useful to some people. Ultimately the way I want to work with these types of APIs is to just simply send them messages and get messages back (asynchronously in this case). To present this interface with the Java client requires:

```Clojure data->Java object->Bytes->IB```

```IB->Bytes->Java object->Clojure Data```

The middle layer always annoyed me when clojure is perfectly capable of sending and receiving bytes on a socket.

But the tradeoff is that you have to figure out the messaging scheme and deal with server and endpoint versions yourself, and this is not easy.

If you only need a few api endpoints in your app it might be worth it to just define helper functions to make calling them with iboga managable rather than depend on many thousands of lines of java (plus plenty of clojure to convert the java objects back to something clojure friendly).

I may eventually add into iboga some ways to make things incrementally easier to work with. Discussion on this is welcome! 

## Usage

You can see an example of how this might be used in `dev/example/core.clj`.

It is useful to start by defining a big constants map to drive the translation of the messages.

```clojure
(ns example.constants)
(def incoming
 {
   1  :tick-price 
   2  :tick-size 
   3  :order-status 
   4  :err-msg
   ;;...
   87 :historical-news-end 
   88 :head-timestamp 
   89 :histogram-data 
   })

(def outgoing
  {:start-api          {:code 71 :version 2 :params [:client-id]} ;;todo:connection options  
   :market-data        {:code   1 :version 11}
   :cancel-market-data {:code 2 :version 1}
   :current-time       {:code 49 :version 1}
   :contract-data      {:code   9 :version 7}
   ;;....
   })
   
   ```
You can get a fancier by adding in  parameters for example and checking them, or defining the expected results for a given response and then turning the response into a map with these keys, but we keep it simple for the example.

Then we add a few helper functions to help use these constants:

```clojure
(ns example.core
  (:require [iboga.core :as iboga]
            [example.constants :as c]))
  
(defn handler
  "Parses the message type of incoming messages"
  [msg]
  (assoc msg 0 (get c/incoming (Integer/parseInt (first msg)) (first msg))))

(defn ->ib
  "Takes a vector prefaced with an outgoing message type keyword and replaces that with the integer id IB uses as well as the message version we're using."
  [msg]
  (-> (get c/outgoing (first msg))
      ((juxt :code :version))
      (into (rest msg))))
```

We then connect to an iboga socket, passing it an ip, port and handler function for incoming messages. This gets us a map containing a `send-fn` and a `close-fn`:

```clojure
(def ib (iboga/socket "127.0.0.1" 7496 (comp prn handler)))
(def send!  (:send-fn ib))
(def close! (:close-fn ib))
````
We start the api:

```clojure

;;start-api with client id 101
(send! (->ib [:start-api 101 ""]))
```
And now we (asynchronously) get back some messages printed out (via our handler function being called on them)!
```clojure
["142" "20181214 17:03:05 EST"]
[:managed-accts "1" "ACCTNUMBER123"]
[:next-valid-id "1" "1"]
[:err-msg "2" "-1" "2104" "Market data farm connection is OK:usfuture.nj"]
[:err-msg "2" "-1" "2104" "Market data farm connection is OK:usfarm.nj"]
[:err-msg "2" "-1" "2104" "Market data farm connection is OK:eufarm"]
...
```
We can get the current time:

```clojure
(send! (->ib [:current-time]))
```

And asynchronously the following is printed:

```
[:current-time "1" "1544940600"]
```

Or get live ticks for AMZN stock:

```clojure
;;requestMktData with ticker-id 123
(send! (->ib [:market-data 123 nil nil "STK"
               nil nil nil nil "SMART" nil
               "USD" "AMZN" nil nil nil nil nil nil]))
```

Note that messages like the above that are passed to IB that require a contract definition will tend to be more brutal like that. A helper function to make this easier is left as an exercise to the reader.

We can cancel the market data with the ticker id we defined above

```clojure
(send! (->ib [:cancel-market-data 123]))
```

Finally we can close our connection with the `close!` function we defined earlier.

```clojure
(close!)
```

## TODO:

* Some sort of error handling and/or logging mechanism (currently just prints some caught exceptions).
* Possibly a namespace of helper functions
* Document the process of delving into the Ib source to discover message shapes

## See also

* [node-ib](https://github.com/pilwon/node-ib): Another "from scratch" IB client for Node.js
* [ib-re-actor](https://github.com/jsab/ib-re-actor): Clojure wrapper for IB's Java api. This is the actively maintained fork, the original project is [here](https://github.com/cbilson/ib-re-actor).
    
## License

Copyright © 2018 Justin Tirrell

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
