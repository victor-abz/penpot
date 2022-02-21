;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.retry
  "A fault tolerance helpers. Allow retry some operations that we know
  we can retry."
  (:require
   [app.common.logging :as l]
   [app.util.services :as sv]
   [promesa.core :as p]))

(defn conflict-db-insert?
  "Check if exception matches a insertion conflict on postgresql."
  [e]
  (and (instance? org.postgresql.util.PSQLException e)
       (= "23505" (.getSQLState e))))

(defn wrap-retry
  [_ f {:keys [::matches ::sv/name]
        :or {matches (constantly false)}
        :as mdata}]

  (when (::enabled mdata)
    (l/debug :hint "wrapping retry" :name name))

  (if-let [max-retries (::max-retries mdata)]
    (fn [cfg params]
      (let [retries (atom 0)]
        ((fn run []
           (-> (f cfg params)
               (p/catch (fn [cause]
                          (if (matches cause)
                            (let [current-retry (swap! retries inc)]
                              (l/trace :hint "running retry algorithm" :retry current-retry)
                              (if (<= current-retry max-retries)
                                (run)
                                (throw cause)))
                            (throw cause)))))))))
    f))

