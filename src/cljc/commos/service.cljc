(ns commos.service
  (:require [#?(:clj clojure.core.async
                :cljs cljs.core.async)
             :refer [chan close!
                     <! >!
                     take! put!
                     alts!
                     tap untap
                     pipe
                     #?@(:clj [go go-loop])]
             :as a])
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go-loop go]])))

(defprotocol IService
  "Commos service protocol.  Please also refer to the docstrings of
  cached and caching."
  (request [this spec ch]
    "Asynchronously put responses to request specified in spec on channel ch.
    Close ch when no more resonses are to be expected.  Only one
    unique channel is allowed per invocation (not per request or spec)
    over the services lifetime.")
  (cancel [this ch]
    "Asynchronously cancel the request associated with channel ch
    and close channel ch."))


;; Cache

(defn caching
  "Services that cache services must invoke them only with this
  wrapper."
  [service cached-service]
  (vary-meta service assoc ::cached-service cached-service))

(defn cached
  "Services that use themselves or pass themselves on to other
  services must use this wrapper on themselves to allow potential
  effects of a caching wrapper."
  [service]
  (::cached-service (meta service) service))

(defn- put-vs
  "Put values onto chs.  Returns a channel which puts true to indicate
  the puts were made and false to indicate that the ch is closed and
  not all vs were put."
  [ch vs]
  (go-loop [[v & vs] vs]
    (if v
      (if (>! ch v)
        (recur vs)
        false)
      true)))

(defn- caching-mult
  [ch accumulate {:keys [forward values]
                  :or {forward :value
                       values list}}]
  (let [tap-ch (chan)
        chs (atom #{})
        dctr (atom nil)
        dchan (chan 1)
        done (fn [_] (when (zero? (swap! dctr dec))
                       (put! dchan true)))
        m (reify
            a/Mux
            (muxch* [_] ch)
            a/Mult
            (tap* [_ ch close?]
              (if close?
                (put! tap-ch ch)
                (let [msg "close?=false not supported"]
                  #?(:clj (throw (UnsupportedOperationException. msg))
                     :cljs (throw msg)))))
            (untap* [_ ch]
              (swap! chs disj ch))
            (untap-all* [_]
              (let [msg "untap-all* not supported"]
                #?(:clj (throw (UnsupportedOperationException. msg))
                   :cljs (throw msg)))))]
    (go-loop [cache nil
              receiving? true]
      (let [[v port] (alts! (cond-> [tap-ch]
                              receiving? (conj ch)) :priority true)]
        (condp identical? port
          tap-ch
          (do (when (or (nil? cache)
                        (<! (put-vs v (values cache))))
                (if receiving?
                  (swap! chs conj v)
                  (close! v)))
              (recur cache
                     receiving?))
          ch
          (if (nil? v)
            (do
              (run! close! @chs)
              (reset! chs nil)
              (recur cache
                     false))
            (let [cache (accumulate cache v)
                  vs (values (case forward
                               :cache cache
                               :value v))]
              (when-let [chs (seq @chs)]
                (reset! dctr (count chs))
                (doseq [ch chs]
                  (take! (put-vs ch vs)
                         (fn [open?]
                           (done nil)
                           (when-not open?
                             (a/untap* m ch)))))
                (<! dchan))
              (recur cache
                     true))))))
    m))

(defn- on-close-pipe
  "Like pipe, but invokes on-close when source closes."
  [source target on-close]
  (let [watch-ch (chan 1 (fn [rf]
                           (completing rf
                                       (fn [result]
                                         (on-close)
                                         (rf result)))))]

    (pipe source watch-ch)
    (pipe watch-ch target)))

(defn- on-close-source
  "Pipes a channel to target and returns it, on-close is invoked when
  it is closed."
  [target on-close]
  (doto (chan)
    (on-close-pipe target on-close)))

(defn cacher
  "Transform a service into a cached service.  

  The cached service internally builds caches for equal request specs
  via accumulate, a reducing fn producing a cache from the current
  cache and a value (from service).  The initial cache is nil.

  Serves requests at cached specs the current cache if it is not nil,
  followed by incoming values according to opts.

  A cache is destroyed when there are no more active requests.

  All requests of a cache must take values to allow new values to be
  streamed.

  
  Opts are

  :forward - Either :cache or :value (default).  If :value,
  incoming values are directly forwarded.  If :cache, the current
  cache (after accumulation) is forwarded in place of incoming values.

  :values - A fn transforming a cache into values to be served.
  Defaults to list."
  [service accumulate & opts]
  (let [request-ch (chan)
        cancel-ch (chan)
        cs (reify
             IService
             (request [this spec target]
               (put! request-ch [spec target]))
             (cancel [this target]
               (put! cancel-ch target)))]
    (go-loop [subs {}
              chs {}]
      (let [[msg port] (alts! [request-ch
                               cancel-ch])]
        (condp identical? port
          request-ch
          (let [[spec target] msg
                [m :as cache]
                (or (get subs spec)
                    (let [ch-in (chan)
                          m (caching-mult ch-in
                                          accumulate
                                          opts)]
                      (request (caching service cs)
                               spec
                               ch-in)
                      [m 0 ch-in]))
                target-step (on-close-source target
                                             #(cancel cs target))]
            (tap m target-step)
            (recur (assoc subs spec (update cache 1 inc))
                   (assoc chs target [spec target-step])))
          cancel-ch
          (let [target msg]
            (if-let [[spec target-step] (get chs target)]
              (let [[m chctr ch-in :as cache] (get subs spec)
                    chctr (dec chctr)
                    chs (dissoc chs target)]
                (untap m target-step)
                (close! target-step)
                (if (zero? chctr)
                  (do
                    (cancel (caching service cs) ch-in)
                    (recur (dissoc subs spec)
                           chs))
                  (recur (assoc subs spec (assoc cache 1 chctr))
                         chs)))
              (recur subs
                     chs))))))
    cs))

;; Combine

(defn combiner
  "Wrapper to create one service from multiple services.
  Requests can be made with

  [k spec]

  as spec where k can be dispatched to a service via k->service."
  [k->service]
  (let [services (atom {})]
    (reify
      IService
      (request [this [k spec] ch]
        (if-let [service (k->service k)]
          (let [pipe (on-close-source ch #(swap! services dissoc ch))]
            (do (swap! services assoc ch [pipe service])
                (request service spec ch)))
          (let [msg (str "Can't find service for " k)]
            #?(:clj (throw (IllegalArgumentException. msg))
                    :cljs (throw msg)))))
      (cancel [this ch]
        (when-let [[subscribed-ch service] (@services ch)]
          (cancel service subscribed-ch)
          (close! subscribed-ch)
          (count (swap! services dissoc ch)))))))

;; Adapter

(defn- id-gen []
  (let [id (atom 0)]
    (fn gen-id []
      (let [return (volatile! nil)]
        (swap! id (fn [id]
                    (vreset! return id)
                    (inc id)))
        @return))))

(defn adapter
  "Service that forwards and coordinates requests and responses.
  
  For each request a unique id is generated and added to the request
  spec.  Matching messages from ch-in are asynchronously put onto the
  corresponding channel.

  request-fn is invoked with a request spec when a request is made.
  cancel-fn is invoked with an id when a request is cancelled and
  should be used to free resources. ch-in is a channel serving
  responses.

  Any following opts may be provided

  :gen-id - Function of 0 args that generates a unique id on each
  invocation.  Defaults to an incremental number generator starting at
  0

  :set-id - Function taking a request spec and an id, returning a new
  request-spec.  Defaults to #(assoc %1 :id %2)

  :get-id - Function returning the id of a response.  Defaults to :id

  :final-msg? - Function returning whether a response is final or more
  should be expected, e. g. in case of subscriptions.  Defaults to
  returning constantly true.

  :on-unexpected-dispatch - Function invoked with a response that is
  not expected.  Note that unexpected responses need to be to
  tolerated since cancellation is not synchronous, i. e. responses may
  have been sent before cancellation has been communicated to the
  origin.  Intended for debugging."
  [request-fn cancel-fn ch-in & {:keys [gen-id set-id get-id final-msg?
                                        on-unexpected-dispatch] :as opts}]
  (let [;; Defaults
        gen-id (or gen-id
                   (id-gen))
        set-id (or set-id
                   #(assoc %1 :id %2))
        get-id (or get-id :id)
        on-unexpected-dispatch (or on-unexpected-dispatch
                                   (constantly nil))
        final-msg? (or final-msg?
                       (constantly true))
        ;; Store
        expected (atom {})
        add! (fn [id ch]
               (swap! expected #(-> %
                                    (assoc-in [:id->ch id] ch)
                                    (assoc-in [:ch->id ch] id))))
        remove! (fn [id ch]
                  (swap! expected #(-> %
                                       (update :ch->id dissoc ch)
                                       (update :id->ch dissoc id))))
        id->ch (fn [id]
                 (get-in @expected [:id->ch id]))
        ch->id (fn [ch]
                 (get-in @expected [:ch->id ch]))]
    (go-loop []
      (when-some [msg (<! ch-in)]
        (let [id (get-id msg)]
          (if-let [ch (id->ch id)]
            (do
              (put! ch msg)
              (when (final-msg? msg)
                (remove! id ch)
                (close! ch)))
            (on-unexpected-dispatch msg)))
        (recur)))
    (reify
      IService
      (request [_ spec ch]
        (let [id (gen-id)
              spec (set-id spec id)]
          (add! id ch)
          (request-fn spec)))
      (cancel [_ ch]
        (when-let [id (ch->id ch)]
          (close! ch)
          (cancel-fn id)
          (remove! id ch))
        (count (:ch->id @expected))))))
