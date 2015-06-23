(ns commos.service-test.helpers
  (:require [commos.service :as service]  
            [#?(:clj clojure.core.async.impl.protocols
                :cljs cljs.core.async.impl.protocols) :refer [Channel]]
            [#?(:clj clojure.core.async
                :cljs cljs.core.async) :refer [chan close!
                                               <! >!
                                               take! put!
                                               alts!
                                               pipe
                                               timeout
                                               #?@(:clj [go go-loop
                                                         <!!])
                                               tap untap] :as a]
            #?@(:clj [[clojure.test :refer :all]]
                :cljs [[cljs.test :refer-macros [is deftest async]]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go-loop go]])))

(defn- chan?
  [x]
  (satisfies? Channel x))

(defn test-within
  "Asserts that ch does not close or produce a value within ms. Returns a
  channel from which the value can be taken."
  [ms ch]
  (go (let [t (timeout ms)
            [v ch] (alts! [ch t])]
        (is (not= ch t)
            (str "Test should have finished within " ms "ms."))
        v)))

(defn test-async
  "Asynchronous test awaiting ch to produce a value or close."
  [ch]
  #?(:clj
     (<!! ch)
     :cljs
     (async done
       (take! ch (fn [_] (done))))))

(defn dummy-service
  "Return a service according to endpoints, a map request-spec->spec,
  where spec is a map of keys:

  :values - A seqable of values or channels.  Channels are expected to
  produce a value or close before subsequent values are provided."
  [endpoints]
  (reify
    service/IService
    (request [this spec target]
      (let [spec (get endpoints spec)]
        (go
          (doseq [v (:values spec)]
            (if (chan? v)
              (when-let [v (<! v)]
                (>! target v))
              (>! target v)))
          (close! target))))
    (cancel [this target]
      (close! target))))
