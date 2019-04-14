# iboga

Experiemental clojure layers on top of Interactive Brokers's API

Iboga contains two approaches to use IB's api. They are both tiny implementations.

This is mostly a playground for interacting with IB from clj. Breaking changes are likely over time


## `iboga.async` [(source)](https://github.com/jjttjj/iboga/tree/master/src/iboga/asnyc.clj)

Depends on [IB's official Java API client](https://www.interactivebrokers.com/en/index.php?f=5041).
Uses reflection and the javac `-parameters` options to generate names based on the names used in the Java source.

To use, you will need to do the following:
1. Download the api source from [http://interactivebrokers.github.io](http://interactivebrokers.github.io). Agree to their terms then choose one of the versions under "Mac / Unix".
2. Extract the zip file and navigate to "IBJts/source/JavaClient"
3. Edit pom.xml to add the line `<compilerArgs><arg>-parameters</arg></compilerArgs>` under the `maven-compiler-plugin` section, to make it look like the following: 

```
<plugins>
    <plugin>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.7.0</version>
        <configuration>
        <source>1.8</source>
        <target>1.8</target>
        <includes>com/**</includes>
        <compilerArgs><arg>-parameters</arg></compilerArgs>
        </configuration>
    </plugin>

```

4. run `mvn install`.
5. Add `[com.interactivebrokers/tws-api "9.73.01-SNAPSHOT"]` to you project.clj dependencies. (replace version string as needed)

[Demo here]((https://github.com/jjttjj/iboga/tree/master/dev/example/async/core.clj))

## `iboga.pure` [(source)](https://github.com/jjttjj/iboga/tree/master/src/iboga/pure.clj)

Does not depend on [IB's official Java API client](https://www.interactivebrokers.com/en/index.php?f=5041). Establishes a socket connection to Trader Workstation, however, you are responsible in application code for managing IB's internal message versioning, which will require digging through the official API client's source code to understand messages you will be receiving. This will more often than not prove to be a nightmare. It may be useful if you call a very small number of API functions, and/or you are extremely reluctant to depend on the huge official client source. [Demo here](https://github.com/jjttjj/iboga/tree/master/dev/example/pure/core.clj)


## See also

* [node-ib](https://github.com/pilwon/node-ib): Another "from scratch" IB client for Node.js
* [ib-re-actor](https://github.com/jsab/ib-re-actor): Clojure wrapper for IB's Java api. This is the actively maintained fork, the original project is [here](https://github.com/cbilson/ib-re-actor).
    
## License

Copyright © 2019 Justin Tirrell

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
