<img src="https://i.imgur.com/ksxyG4I.jpg" width=400 align=right />

# Iboga

Iboga is a data driven clojure wrapper around Interactive Brokers' Java api client.

```clojure
(require '[iboga.core :as ib])

(def conn (ib/client))

(ib/connect conn "localhost" 7497)

(def log (atom []))

(defn handler1 [[msg-key msg-data :as msg]]
  (when (= (:id msg) 123)
    (printf "new %s message: %s" msg-key)
    (swap! log conj msg)))

(ib/add-handler conn handler1)

(def SPY
  {:local-symbol "SPY"
   :sec-type     "STK"
   :exchange     "SMART"
   :currency     "USD"})

(ib/req conn [:contract-details
              {:id 123 :contract SPY}])

@log

;;later:
;;(ib/remove-handler conn handler1)
```

More complete examples can be seen in the [examples directory](examples/):

* [tutorial1](examples/tutorial1.clj): covers historical data requests and discovering arguments from the repl.
* More examples coming soon...

# Status

Alpha. Only a small portion of the IB API has been tested and there are known limitations and edge cases that haven't been addressed. My goal in releasing Iboga is to get feedback on the overall design of the project before attempting covering a large amount of the api. 

All feedback, issues and PRs welcome.

# Installation

**First you will need to [install the Java API Client](https://github.com/jjttjj/iboga/wiki/Installing-the-Java-API-client)**

Once the api client is installed locally you can now add it and iboga to your dependencies.

```clojure
 ;;leiningen
[com.interactivebrokers/tws-api "9.73.01-SNAPSHOT"]
[io.jex/iboga "0.3.3"]
```
```clojure
 ;;deps.edn
com.interactivebrokers/tws-api {:mvn/version "9.73.01-SNAPSHOT"}
io.jex/iboga {:mvn/version "0.3.3"}
```
[![Clojars Project](https://img.shields.io/clojars/v/io.jex/iboga.svg)](https://clojars.org/io.jex/iboga)

## Messages 

Iboga lets you send messages to and receive messages from Interactive Brokers. Messages look like:

```clojure
[<msg-key> <msg-data>]
```

There are two kinds of messages, "request" and "receive". 

Requests correspond to the methods of IB's [EClient class](http://interactivebrokers.github.io/tws-api/classIBApi_1_1EClient.html) and are made by passing a message to the `iboga.core/req` function.

Receive messages correspond to [EWrapper](http://interactivebrokers.github.io/tws-api/interfaceIBApi_1_1EWrapper.html) and are handled by registering a handler on your connection with `iboga.core/add-handler`. Each handler receives every incoming message.

# Names

The terminology Interactive Brokers uses within their code is attempted to be "clojurified" in Iboga. This mostly just means converting from camelCase to kebab-case, with explicit handling of edge cases plus some intentional renames.

# Discoverability

It is possible to explore Iboga from the repl. 

If know which EClient/EWrapper method you need to use, you can use `msg-name-search` to find the corresponding `msg-key` to use for the request or handler:

```clojure
(ib/msg-name-search "hist")
;;=>
(["com.ib.client.EClient/cancelHistogramData" :cancel-histogram-data]
 ["com.ib.client.EClient/cancelHistoricalData" :cancel-historical-data]
 ["com.ib.client.EClient/reqHistogramData" :histogram-data]
 ["com.ib.client.EClient/reqHistoricalData" :historical-data]
 ["com.ib.client.EWrapper/historicalData" :historical-data]
 ["com.ib.client.EWrapper/historicalDataEnd" :historical-data-end]
 ;;<clipped>
)

```

Iboga integrates with `clojure.spec.alpha` and we can lean on it to learn about our api.

```clojure
(require [clojure.spec.alpha :as s])
```

In Iboga, there is a **spec-key** for each of the following things in the Interactive Brokers API:

* Each method in EClass and EWrapper, and each parameter in those methods. 
* Each "data class" (Contract, Order, etc) and each method which represents a getter/setter of that class.

To get the full spec-key for a request msg-key, use the function `req-spec-key`. Spec-Keys are mainly used internally, generally the short unqualified version can be used with Iboga. But the spec-key is also used for the spec key and we can use this to describe the spec for a request:

```clojure
(ib/req-spec-key :historical-data) ;;=> :iboga.req/historical-data
(s/describe (ib/req-spec-key :historical-data))

;;=>
(keys
 :req-un
 (:iboga.req.historical-data/id
  :iboga.req.historical-data/contract
  :iboga.req.historical-data/end
  :iboga.req.historical-data/duration
  :iboga.req.historical-data/bar-size
  :iboga.req.historical-data/show
  :iboga.req.historical-data/rth?
  :iboga.req.historical-data/format-date
  :iboga.req.historical-data/update?
  :iboga.req.historical-data/chart-options))
```

We can now see the required keys that a `:historical-data` request takes. Note that the keys that are returned from an `s/describe` call are qualified but we can use unqualified keys in our requests.

We can also see what the spec for each of the arguments themselves is. 

`req-spec-key` takes as an optional second argument a key representing one of the above arguments:

```clojure
(s/describe (ib/req-spec-key :historical-data :id)) 
;;=> #function[clojure.core/number?]
;;identical to (s/describe :iboga.req.historical-data/id))

(s/describe (ib/req-spec-key :historical-data :contract))
;;=>
(keys
 :opt-un
 [:iboga.contract/currency
  :iboga.contract/strike
  :iboga.contract/combo-legs
  :iboga.contract/right
  :iboga.contract/trading-class
  :iboga.contract/exchange
  :iboga.contract/include-expired
  :iboga.contract/primary-exch
  :iboga.contract/combo-legs-descrip
  :iboga.contract/multiplier
  ...])
;;the contract in teh `:historical-data` request is itself a map with optional unqualified keys
```

So `:id` is a number and `:contract` is another map with a bunch of optional keys. All IB "data classes" such as [Order](http://interactivebrokers.github.io/tws-api/classIBApi_1_1Order.html) and [Contract](http://interactivebrokers.github.io/tws-api/classIBApi_1_1Contract.html) in interactive brokers are just spec'ed maps in Iboga.

# Handlers

To handle received messages, you can pass one or more handler functions to a client when you create it, for example:

```clojure
(def log (atom []))
(def conn (ib/client println #(swap! log conj %)))
```
Will pass all messages to println and our anonymous function which `conj`es all messages to an atom.

We can also use `ib/add-handler` to add a new handler:

```clojure
(defn bar-handler [[msg-key data :as msg]]
  (when (:bar data)
    (println (:bar data))))

(ib/add-handler conn bar-handler)
```

Remember _all_ messages will be passed all handlers, so a handler should properly filter only the messages it wants to handle.

To remove a handler:

```clojure
(ib/remove-handler conn bar-handler)
(ib/remove-handler conn println)
```

Currently the only way to remove a handler is to pass `remove-handler` the identity of the function (this may change in future versions of iboga), so we cannot remove the anonymous logging function we passed to the client when we created it. If you plan to remove a handler you must keep a reference to the handler.

# Requests

As described above a request message looks like:

```clojure
[<req-key> <arg-map>]
```

The `req-key` is an unqualified keyword describing the req type. The `arg-map` is a map of unqualified argument keys to their values.

All "data types" used as arguments such as `contract`s and `order`s should be given as unqualified maps.

To make a request, pass a client and a request message to the `iboga.core/req` function. 

Remember to set up your handlers before making a request. 

`req` takes two arguments, a `client` and a message.

See [tutorial1](examples/tutorial1.clj) for some request examples.

## Specs

Spec validation for all requests is currently turned on by default. This may change in the future.
You can use `ib/validate-reqs` to toggle the validation:

```clojure
(ib/validate-reqs false) ;;turns off spec validation for all requests
(ib/validate-reqs true)  ;;turns is back on

```

# See also

  * [ib-re-actor](https://github.com/jsab/ib-re-actor): Clojure wrapper for IB's Java api. This is the actively maintained fork, the original project is [here](https://github.com/cbilson/ib-re-actor).
  * [aws-api](https://github.com/cognitect-labs/aws-api): Iboga's data driven design is heavily influenced by the design of `cognitect-labs/aws-api`.
  * [sente](https://github.com/ptaoussanis/sente): provided inspiration for the shape of messages in Iboga.



# License

Copyright © 2019 Justin Tirrell

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version. https://opensource.org/licenses/EPL-1.0

Header Image:
"Tabernanthe iboga plant with mature fruit" by Marco Schmidt, used under <a href="https://creativecommons.org/licenses/by-sa/3.0/legalcode">CC BY-SA 3.0</a>. Filter applied to original.
