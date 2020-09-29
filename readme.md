# IBoga

Iboga is a data driven clojure wrapper around Interactive Brokers' Java api client.

Iboga is currently very experimental and will be subject to frequent breaking changes.


## Installation

Iboga requires that the the Java tws-api client be compiled with the javac `--parameters` flag to enable access to parameter names via reflection. The following tool does the installation for you in a single command.

https://github.com/jjttjj/tws-install


Once that is done, you will need to add the iboga dependency as well as the tws dependency to your `deps.edn` to use iboga in your project.

```
com.interactivebrokers/tws-api {:mvn/version "979.01-with-parameters"}
dev.jt/iboga {:git/url "https://github.com/jjttjj/iboga.git"
             :sha     "0c99d5cdf5389da30cd095b947d779d15c2b92b5"}
```

## Usage

See [examples](examples/examples1.clj) for current usage examples.

## License

Copyright © 2020 Justin Tirrell

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version. https://opensource.org/licenses/EPL-1.0
