(ns commos.service-test
  (:require #?@(:clj [[clojure.test :refer :all]]
                :cljs [[cljs.test :refer-macros [is deftest]]])
            [commos.service :as service]
            [commos.service-test.helpers :refer [test-within
                                                 test-async
                                                 dummy-service]]
            [#?(:clj clojure.core.async
                :cljs cljs.core.async) :refer [chan close!
                                               <! >!
                                               put!
                                               alts!
                                               pipe
                                               #?@(:clj [go go-loop])
                                               tap untap] :as a])
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]])))

(deftest it-caches
  (let [service (-> {0 {:values [(a/to-chan (range 2)) ;; produce
                                                       ;; different
                                                       ;; val if
                                                       ;; second subs
                                                       ;; is made
                                 (chan) ;; prevent closing the source
                                        ;; which might trigger cache
                                        ;; rebuilding before second
                                        ;; subs
                                 ]}}
                    (dummy-service)
                    (service/cache (fn [_ v] v)))
        target (memoize (fn [_] (chan)))]
    (service/request service 0 (target 0))
    (test-async
     (test-within 1000
       (go
         ;; ensure the mult is done with the val
         (<! (target 0))
         (service/request service 0 (target 1))
         (is (= 0 (<! (target 1))) "Got cache on target 1"))))))

(deftest it-forwards-value
  (let [v-ch (a/to-chan (range 2))
        break (chan)
        service (-> {0 {:values [v-ch
                                 break
                                 v-ch]}}
                    (dummy-service)
                    (service/cache (fn [_ _] :cache)))
        target (chan)]
    (service/request service 0 target)
    (test-async
     (test-within 1000
       (go
         (<! target) ;; 0 or :cache (expected race condition)

         ;; prevent cache building up completely and triggering
         ;; rebuild before tap is in effect:
         (close! break)
         (is (= 1 (<! target)) "Value forwarded"))))))

(deftest it-forwards-cache
  (let [v-ch (a/to-chan (range 2))
        break (chan)
        service (-> {0 {:values [v-ch
                                 break
                                 v-ch]}}
                    (dummy-service)
                    (service/cache (fn [_ _] :cache)
                                   :forward :cache))
        target (chan)]
    (service/request service 0 target)
    (test-async
     (test-within 1000
       (go
         (<! target) ;; :cache, but not necessarily forwarded
         
         ;; prevent cache building up completely and triggering
         ;; rebuild before tap is in effect:
         (close! break)
         (is (= :cache (<! target)) "Cache forwarded"))))))
