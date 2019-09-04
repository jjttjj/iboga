(ns iboga.ine
  (:require [iboga.core :as iboga]
            [iboga.ine.defaults :as dflt]))

(dflt/def-req-specs)

#_(defn req [conn request]
  (-> (iboga/req-ctx conn request)
      iboga/assert-connected
      iboga/ensure-argmap
      iboga/maybe-validate
      dflt/add-default-args
      iboga/send-req))
