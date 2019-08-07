<img src="https://i.imgur.com/ksxyG4I.jpg" width=400 align=right />

# Iboga

Iboga is a data driven clojure wrapper around Interactive Brokers' Java api client. 

```clojure
(require '[iboga.core :as ib])

(def conn (ib/client))

(ib/connect conn "localhost" 7497)

(defn handler1 [[msg-key msg]]
  (when (= (:id msg) 123)
    (printf "new %s message: %s\n" msg-key msg)))

(ib/add-handler conn handler1)

(def contract
  {:local-symbol "SPY"
   :sec-type     "STK"
   :exchange     "SMART"
   :currency     "USD"})

(ib/req conn [:historical-data
              {:id       123
               :contract contract
               :duration "1 W"
               :bar-size "1 hour"}])

;;later:
;;(ib/remove-handler conn handler1)
```

More complete examples can be seen in the [examples directory](examples/):

* [tutorial1](examples/tutorial1.clj): covers historical data requests and discovering arguments from the repl.
* [tutorial2](examples/tutorial2.clj): covers higher level functionality, contract details requests and placing orders (including complex multi-legged orders).

# Status

Alpha. Only a small portion of the IB API has been tested and there are known limitations and edge cases that haven't been addressed. My goal in releasing Iboga is to get feedback on the overall design of the project before attempting covering a large amount of the api. 

All feedback, issues and PRs welcome.

# Installation

Iboga depends on [IB's official Java API client](https://www.interactivebrokers.com/en/index.php?f=5041) which unfortunately isn't available in a public maven repo. To use iboga you will need to install the official Java client locally with Maven ([maven install instructions](https://maven.apache.org/install.html)).

To install the IB Java client locally

1. Download the api source from [http://interactivebrokers.github.io](http://interactivebrokers.github.io). Agree to their terms then choose one of the versions under "Mac / Unix".
2. Extract the zip file and navigate to "IBJts/source/JavaClient"
3. run `mvn install`.
4. Add one of the following to your deps.edn or project.clj dependencies (replace version string as needed):
   ```
   [com.interactivebrokers/tws-api "9.73.01-SNAPSHOT"]
   ```
   ```
   com.interactivebrokers/tws-api {:mvn/version "9.73.01-SNAPSHOT"}
   ```

Now you can add the Clojars Iboga dependency

```clojure
[io.jex/iboga "0.3.1-SNAPSHOT"] ;;leiningen
```
```
io.jex/iboga {:mvn/version "0.3.1-SNAPSHOT"} ;;deps.edn
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
;;=>
(keys
 :req
    [:iboga.req.historical-data/id :iboga.req.historical-data/contract]
 :opt
 [:iboga.req.historical-data/end
  :iboga.req.historical-data/duration
  :iboga.req.historical-data/bar-size
  :iboga.req.historical-data/show
  :iboga.req.historical-data/rth?
  :iboga.req.historical-data/format-date
  :iboga.req.historical-data/update?
  :iboga.req.historical-data/chart-options])
```

We can see above that a historical-data request requires a `:id` and a `:contract`

We can also see what the spec for each of the arguments themselves is. 

`req-spec-key` takes as an optional second argument a key representing one of the above arguments:

```clojure
(s/describe (req-spec-key :historical-data :id)) 
;;=> #function[clojure.core/number?]
;;(identical to (s/describe :iboga.req.historical-data/id)))


(s/describe (req-spec-key :historical-data :contract))
;;=>
(keys
 :opt
 [:iboga.contract/local-symbol
  :iboga.contract/sec-type
  :iboga.contract/symbol
  :iboga.contract/currency
  :iboga.contract/sec-id
  ;;<clipped>
  ])
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
[<msg-key> <msg-data>]
```

The `msg-key` is an unqualified keyword describing the req type. The `msg-data` can be either of the following:

* **argmap**: A map of unqualified argument keys to their values. Optional arguments can be omitted
* **argvec**: A vector of argument values. Any number of trailing optional arguments can be omitted (but all values preceding an argument that you'd like to use must be included.

Vector arguments must be in the order given with `s/describe` (see the section "discoverability" above), with all `:req`uired arguments followed by the `:opt`ional arguments. The order is the same as parameters of the corresponding method in the [EClient class](http://interactivebrokers.github.io/tws-api/classIBApi_1_1EClient.html).

All "data types" used as arguments such as `contract`s and `order`s should be given as unqualified maps.

To make a request, pass a client and a request message to the `iboga.core/req` function. 

Remember to set up your handlers before making a request. 

`req` takes two arguments, a `client` and a message.

```clojure
(def log (atom [])
(def conn (ib/client #(swap! log conj %)))
(ib/connect conn "localhost" 7497)

(def contract {:local-symbol "SPY" :sec-type "STK" :exchange "SMART" :currency "USD"})

(ib/req conn [:historical-data {:id 123 :contract contract :duration "1 W" :bar-size "1 hour"}] )
;;OR
(ib/req conn [:historical-data [123 ;;:id
                                contract ;;:contract
                                ;;unlike above, :end key cannot be omitted if
                                ;;we want the subsequent arguments 
                                ;;(duration and bar-size) defined
                                (java.time.LocalDateTime/now) ;;:end
                                "1 W" ;;:duration
                                "1 hour" ;;bar-size
                                ]])
```

## Specs

Spec validation for all requests is currently turned on by default. This may change in the future.
You can use `ib/validate-reqs` to toggle the validation:

```clojure
(ib/validate-reqs false) ;;turns off spec validation for all requests
(ib/validate-reqs true)  ;;turns is back on

```

# Higher level api

While iboga makes the IB api client easier to use it is still fairly low level. One must still work on the level of incoming and outgoing messages when often what is really needed is a function call that synchronously returns the result of a request, or passes only the relevant data of asynchronous responses messages, without having to worry about messages. 

Currently [wip.clj](src/iboga/wip.clj) exposes some higher level functionality, but I'm not sure manually wrapping the requests like this is the way to go and this namespace is likely to be deprecated at some point.

I believe it might be possible to provide metadata, such as which response message types are expected for a given request type, what the "response finished" message type will be, etc, to expose a higher level api in a more automatic manner.

The `wip.clj` functions can be used as follows:

```clojure
(require '[iboga.wip :as wip])

(def contract {:local-symbol "SPY" :sec-type "STK" :exchange "SMART" :currency "USD"})

;;These synchronously return results
;;both argmap or argvec can still be used, but :id arg must be omitted
;;requests of no arguments do not need an argmap or argvec
(wip/current-time conn)
(wip/contract-details conn [contract])
(wip/historical-data conn {:contract contract :bar-size "1 day" :duration "1 Y"})

```

Only a very small portion of the functionality that could be wrapped like this is currently. Feel free to open an issue or submit a PR for any functionality you need here. 

# Customization

**Iboga Is a Work In Progress**

The IB api has a large surface area. Iboga the main features Iboga adds are:

* "clojurization"/datafication of things that are represented as objects in the Java API client
* default values and optional omissions for requests
* specs 

Theses features require attention and thought be given for each request type received message types.

Currently only a small percentage of the API surface area is complete in Iboga. It is a goal to have these features implemented for the entire API, but in the meantime users can implement the individual features you need if they are not provided.

Each spec-key in Iboga can have the following schema attributes set for it:

* `:default-value`: A default value for a request argument spec-key
* `:default-fn`: Request argument spec-keys can also have a functionally generated default. `:default-fn` should be a function which takes as argument an argument map with qualified spec-keys as keys, and which already has any `:default-value`s added, and returns a default value.
* `:to-ib`: Any spec-key for an argument or "data-type" field can have a `:to-ib` function which translates it to the value recognized by IB.
* `from-ib`: Any received message-data key spec-key or "data class" field can have a `from-ib` function which transforms it from the value provided by IB to a clojurey value.

The `set-schema!` function can be used to set schema attributes.

```clojure
(ib/set-schema! :iboga.req.real-time-bars/bar-size :default-value "1 day")
;;or the whole attributes map can be set
(ib/set-schema! :iboga.req.historical-data/end
                {:default-fn (fn [_argmap] (ZonedDateTime/now))
                 :to-ib #(.format (DateTimeFormatter/ofPattern "yyyyMMdd[  HH:mm:ss]")
                                  %)})
```

Specs should then be defined separately:

```clojure
(s/def :iboga.req.historical-data/end #(instance? ZonedDateTime %))
````

For a more thorough example of customization see [this example](examples/customization.clj).

We are happy to accept pull request and new issues for your customizations to be added to the default implementation!


# Roadmap

### Names

I am considering allowing users to customize the naming of spec-keys, ie, translation from the names IB uses for their methods/classes/parameters. 

## Tests

Tests are needed. Generative testing should be immensely useful given that most of the functionality can be represented with data that can in theory be generated. However, there is work to be done before this is possible.

## Performance

Measuring and improving performance is a goal.


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
