(ns commos.run-tests
  (:require [cljs.nodejs :as nodejs]
            [cljs.test :refer-macros [run-tests]]
            [commos.service-test]))

(nodejs/enable-util-print!)

(defn main []
  (run-tests 'commos.service-test))

(set! *main-cli-fn* main)
