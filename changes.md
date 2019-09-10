# Iboga 0.3.3

## 0.3.3 / 2019-09-10
### BREAKING:
* Remove the ability to use an argument vector or argument map to specify arguments for request. All request arguments must now be maps.
* Default/optional arguments are no longer handled by iboga. All arguments required by Interactive Broker's API are now required by Iboga.
* Custom transformers for values to and from IB are now registered via `iboga.core/def-to-ib` and `iboga.core/def-from-ib` which take a fully qualified keyword, and a function of the value to be transformed.