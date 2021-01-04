# Maintaining a non-aggregated Limit Order Book for Bitcoin with the Coinbase Pro api in clojure

## Let's make a LOB!

Let's make a limit order book from the coinbase pro api.

This is a walkthrough of the code [here](src/lmalob.clj).

A limit order book is a data structure containing all resting orders for a market. For each price level it contains information on the liquidity available for that price.

Often a LOB a data source provides might be aggregated, where for each price level, we can see only the total size available at that price. Coinbase's websocket api contains a convenient channel to subscribe to for this. If you subscribe to the `level2` channel, you will receive a huge message with `"type"` `"snapshot"` that will contain the current coinbase aggregated LOB, followed by subsequent messages containing batched changes to make to this snapshot. 

We want a challenge though, so we will make a non-aggregated LOB where every single individual order will be kept. So we need to store them in a way that we can 
1. Add an order in such a way that it's price/time priority position relative to all other orders can be examined
2. Cancel an individual order. 

A non-aggregated LOB let's us analyze information that is lost in an aggregated LOB, such as the rate of cancellations, the exact queue position of a particular order, patterns in order placement and cancellations which may hint at an origin or possible intent of these orders, and so on.

Our goal is to turn the [Coinbase Pro Full Channel](https://docs.pro.coinbase.com/#the-full-channel) into a non-aggregated LOB that's kept up to date in real time.

For this we'll need a websocket connection to use the feed, an http client to request the initial LOB data to build off of, a json parser to parse the data Coinbase gives us

### Dependencies

```clojure
{
  org.clojure/clojure         {:mvn/version "1.10.2-rc1"}
  org.clojure/data.json       {:mvn/version "1.0.0"}
  java-http-clj/java-http-clj {:mvn/version "0.4.1"}
  org.clojure/core.async      {:mvn/version "1.2.603"}
  dev.jt/lob                  {:git/url "https://github.com/jjttjj/lob.git"
                               :sha     "f616f3c0728f0a9cd016093d552e1c9aa0fb72a8"}

  }
```

The most notable dependency is probably [java-http-clj](https://github.com/schmee/java-http-clj) which offers a minimal wrapper over the built in `java.net.http` HTTP and websocket clients. I've been using this lately as a no-frills http/websocket client and it has served me well so far. It's an extremely small wrapper on top of the built in java http libraries and uses no dependencies itself. 

### Requires

```clojure
(ns lmalob
  (:require [java-http-clj.core :as http]
            [java-http-clj.websocket :as ws]
            [clojure.data.json :as json]
            [clojure.core.async :as a :refer
             [<! <!! >! >!! alt! alts! chan go go-loop poll!]]
            [dev.jt.lob :as lob]))
```

### Util

```clojure
(defn info [& xs] (apply println xs))

(defn uuid [s] (java.util.UUID/fromString s))

(defn cf->ch [^java.util.concurrent.CompletableFuture cf ch]
  (.whenCompleteAsync cf
    (reify
      java.util.function.BiConsumer
      (accept [_ result exception]
        (a/put! ch (or result exception)))))
  ch)

```

I'll use `info` as a placeholder for some real logging I may eventually want to do. `uuid` parses uuids strings. 
`cf->ch` puts the result of a java `CompletableFuture` onto a core.async `chan`. The `java-http-clj` library gives us the option to have results returned on a `CompletableFuture` to use them asynchronously. This helper function lets me use core.async instead.

First we will need a way to interact with both the REST and websocket APIs coinbase offers. There are many options for these things today in clojure land. Recently I've been using [java-http-clj](https://github.com/schmee/java-http-clj) which offers a minimal wrapper over the built in `java.net.http` HTTP and websocket clients, and uses no dependencies.


### API notes

Coinbase is kind enough to offer their real data publicly. We don't technically need to use the sandbox URLs, since we're not using any endpoints that are potentially dangerous (such as placing orders). However, the full LOB data we're requesting is huge (~20MB per request), and the sandbox versions are significantly smaller (1-2MB) so it is polite of us (and prevents our IP from being potentially banned for abuse) to use the sandbox endpoints until we ready to try things out with the real feed.

```clojure
(def rest-url "https://api.pro.coinbase.com")
(def sandbox-rest-url "https://api-public.sandbox.pro.coinbase.com")
```

For this demo we used Bitcoin, but any of the currencies Coinbase offers works just as well, just replace "BTC-USD" with the appropriate product id. You can see all products with the following code. Note that the sandbox API provides much fewer products than the "real" one.

```clojure

(-> (http/get (str sandbox-rest-url "/products"))
    :body
    (json/read-str :key-fn keyword)
    (->> (map :id)))
```


## The LOB snapshot

We'll need a snapshot to use as a starting point to build the current LOB from. 

The [Order Book](https://docs.pro.coinbase.com/#get-product-order-book) endpoint, with the `level` parameter set to 3, will give us a good starting point.

```
(http/get (str sandbox-rest-url "/products/BTC-USD/book?level=3"))

```

The returned JSON looks like:

```javascript
{
    "sequence": "3",
    "bids": [
        [ "295.96","0.05088265","3b0f1225-7f84-490b-a29f-0faef9de823a" ],
        ...
    ],
    "asks": [
        [ "295.97","5.72036512","da863862-25f4-4868-ac41-005d11ab0a5f" ],
        ...
    ]
}
```

It is important that the we parse the prices as `bigdec` because they will serve as keys in our lob, and doubles make for bad map keys because of their [rounding errors](https://docs.oracle.com/cd/E19957-01/806-3568/ncg_goldberg.html). We'll make `size` a bigdec as well.

The `:sequence` value represents a number that is guaranteed to be increasing with time. When comparing two messages, a higher sequence number will mean that message definitely occurred later. This will be important for us later.

We also want to make our requests asynchronously because these are big/slow requests and we'll have stuff to do while waiting for the response

```clojure
(defn request-ch [req-map ch]
  (-> (http/send-async req-map)
      (cf->ch ch)))

(defn parse-level3-val [k v]
  (case k
    (:asks :bids) (mapv (fn [[px sz id]] [(bigdec px) (bigdec sz) (uuid id)]) v)
    ;;else return unchanged string
    v)
  )
(defn parse-level3 [s]
  (json/read-str s
    :key-fn keyword
    :value-fn parse-level3-val))

(defn req-level3 [sandbox? ch]
  (let [base   (if sandbox? sandbox-websocket-url rest-url)
        result (chan 1 (map (fn [response] (parse-level3 (:body response)))))]
    (request-ch 
      {:method :get
       :uri    (str base "/products/BTC-USD/book?level=3")}
      result)
    (a/pipe result ch)))
```

Now we can conveniently request the level3 data be parsed into nice clojure types and put onto a channel we provide:

```clojure
(<!! (req-level3 true (a/chan)))
```

```clojure
{:bids [[30997.47M 3009.9993548M #uuid "38bdbefd-bdf7-4b91-abf4-d995fe12682f"]
        [30997.46M 4771M #uuid "8b4e1e8f-158b-4a6c-bf9f-d99ecb318e39"]
        [30997.45M 6020M #uuid "555e571b-3198-498d-bfea-09bf0a4f11ef"]
        ...],
 :asks [[30997.49M 3009.99292034M #uuid "a84b9fe5-1b1a-4dbd-9fa8-3829cb279f6d"]
        [30997.5M 4771M #uuid "1fdb4052-5c9e-4f16-b168-ea44ea0a5f40"]
        [30997.51M 6020M #uuid "def3dfc6-7d9b-44b9-8ee8-80e23181d83c"]
        ...],
 :sequence 243571813}
```

Now we need to get that data into a structure that will let us efficiently add and remove orders. I released the hilariously small [https://github.com/jjttjj/lob](lob) "library" for this purpose. 

```clojure
(defn cb-level3->lob [{:keys [asks bids sequence] :as cb-lob}]
  (as-> (lob/empty-lob) lob
    (reduce (fn [lob [px sz id time]] (lob/insert lob ::lob/asks px id nil sz)) lob asks)
    (reduce (fn [lob [px sz id time]] (lob/insert lob ::lob/bids px id nil sz)) lob bids)
    (assoc lob :sequence sequence)))
```

One thing to note is that the Coinbase level 3 lob gives us order ids for each order but not the time they were placed. So we `lob/insert` them with a time of `nil`. In effect this will cause all the orders initialized in this lob to behave as if they were added before any orders which ARE `lob/insert`ed with a time value. This is good enough for our usage.

```clojure
(cb-level3->lob (<!! (req-level3 true (a/chan))))

;;=>
{:dev.jt.lob/asks {31089M {#uuid "0d4982a1..." [nil 3009.99903649M]},
                   31089.01M {#uuid "7c517469..." [nil 4771M]},
                   31089.02M {#uuid "e9dba70f..." [nil 6020M]},
                   ...},
 :dev.jt.lob/bids {31088.98M {#uuid "18a0459a..." [nil 3009.9993567M]},
                   31088.97M {#uuid "fd7aa4f0..." [nil 4771M]},
                   31088.96M {#uuid "b865e258..." [nil 6020M]},
                   ...},
 :sequence 243573659}
```

## The Websocket Feed

Now we have a base LOB to work off of, it's time to update it with a websocket connection.

```clojure
(def websocket-url "wss://ws-feed.pro.coinbase.com")
(def sandbox-websocket-url "wss://ws-feed-public.sandbox.pro.coinbase.com")

```

`ws->clj` is a function to establish a websocket connection, puts the websocket result object on a core.async channel and puts every text message received on the socket onto a seperate channel. We'll also report the status changes with our `info` function.

```clojure
(defn ws->ch [url open-ch recv-ch]
  (ws/build-websocket url
    {:on-text  (let [sa (atom "")]
                 (fn [ws s last?]
                   (let [s (swap! sa str s)]
                     (when last?
                       (a/put! recv-ch s)
                       (reset! sa "")))))
     :on-open  (fn [ws]
                 (info "ws opened")
                 (a/put! open-ch ws))
     :on-close (fn [ws status reason] (info "ws closed. status:" status "reason:" reason))
     :on-error (fn [ws throwable] (info throwable "ws error"))})) 
```

Let's give this a spin:

```clojure
(def recv1 (a/chan (a/sliding-buffer 10)))
(def ws1 (a/promise-chan))

(ws->ch websocket-url ws1 recv1)

(ws/send (<!! ws1)
  (json/write-str
    {:type        "subscribe"
     :product_ids ["BTC-USD"]
     :channels    ["full"]}))

(a/poll! recv1)
;;=>
"{\"type\":\"done\",\"side\":\"sell\",\"product_id\":\"BTC-USD\",\"time\":\"2021-01-04T17:51:47.560057Z\",\"sequence\":19370793361,\"order_id\":\"10fe8869-7837-4180-867a-393f77b8b93d\",\"reason\":\"canceled\",\"price\":\"30734.96\",\"remaining_size\":\"0.38\"}"

(ws/close (<!! ws-ch))
```

## The full feed

We only care about two message types from the [full](https://docs.pro.coinbase.com/#the-full-channel) websocket feed. "open" and "done" messages which indicate an order should be added or removed from the LOB. So we'll need to parse messages of those types, and identify those messages:

```clojure
(defn parse-websocket-value [k v]
  (case k
    :time                                         (java.time.Instant/parse v)
    (:size :price :remaining_size :funds)         (bigdec v)
    (:type :product_id :side :order_type :reason) (keyword v)
    (:order_id :maker_order_id :taker_order_id)   (uuid v)
    ;;else
    (when (not= "" v)
      v)))

(defn parse-ws-msg [s]
  (json/read-str s :key-fn keyword :value-fn parse-websocket-value))
  
(defn lob-msg? [{:keys [type]}]
  (or (identical? type :open)
      (identical? type :done)))
```

Let's try getting some of these messages. We'll filter the lob messages and make sure everything is parsed.

```clojure
(def recv2 (a/chan 20000 (comp (map parse-ws-msg) (filter lob-msg?))))
(def ws2 (a/promise-chan))
(ws->ch websocket-url ws2 recv2)
(ws/send (<!! ws2)
  (json/write-str
    {:type        :subscribe
     :product_ids ["BTC-USD"]
     :channels    ["full"]}))
(a/poll! recv2)
(a/poll! recv2)
(ws/close (<!! ws2))
```

```clojure
{:type :open,
 :side :buy,
 :product_id :BTC-USD,
 :time #object[java.time.Instant ...],
 :sequence 19371778786,
 :price 31183.78M,
 :order_id #uuid "...",
 :remaining_size 0.06M}
 
{:remaining_size 0.01M,
 :product_id :BTC-USD,
 :time #object[java.time.Instant ...],
 :type :done,
 :reason :canceled,
 :order_id #uuid "...",
 :sequence 19371778789,
 :side :buy,
 :price 30935.57M}
```

Next we want functions to add one or more of these messages to our LOB data structure:

```clojure
(defn with-msg [lob {:keys [type time price order_id remaining_size side sequence]}]
  (when-let [k (#{:done :open} type)] 
    (let [lob-side (case side :buy ::lob/bids :sell ::lob/asks)]
      (->
        (case k
          :open (lob/insert lob lob-side price order_id time remaining_size)
          ;; presumes that delete is noop for orders not in book
          :done (lob/delete lob lob-side price order_id))
        (assoc :time time :sequence sequence)))))

(defn with-msgs [lob msgs]
  (reduce with-msg lob msgs))
```

## Syncing up the feed and initial LOB

Now for the tricky part. Remember the LOB we got from the rest api has a `:sequence` number? So do all the messages we receive on the websocket feed. The sequence number represents the order of these events occuring in time. In practice I've found that often when you request a LOB via the rest api and start a websocket full feed at roughly the same time, the sequence number of the lob result will be behind the sequence number of the first websocket feed message. This is not good, because it means that there are messages that have occured between the LOB being generated and the messages we receive from the feed, meaning our LOB would not be fully accurate. It seems that the LOB is generally a few seconds behind the websocket feed. To fix this we need to 

1. Collect all messages from the "full" feed, making note of the first sequence number
2. Wait a few seconds, then request the level 3 LOB, while continuing to collect messages from the feed.
3. When we get the level 3 LOB, compare its sequence number to the first feed message. 
   - If the level 3 LOB's sequence number is greater than or equal to the sequence of the first feed message, then drop all messages from the feed with a sequence number less than the level 3 LOB, merge those into the lob with `with-messages` and this will be our initial LOB. We also need to immediately stop taking messages from the feed so every message occuring after the Level 3 LOB sequence number will be added to our LOB.
   - If the level 3 LOB's sequence number is less than the first feed messages, we need to try again. `GOTO 2.`
4. Now we have an initial LOB and a channel which contains all the "full" feed messages occuring after this initial LOB.


```clojure
(defn get-initial-lob
  "Takes a map with the following keys:
  :out - a channel on which the resulting initial LOB will be put.
  :in - a channel of :open and :done messages from coinbase's full feed. Values will be consumed from this feed as necessary until the :sequence number of the messages surpass that of the initial LOB.
  :init-delay - the delay in milliseconds to wait before initially requesting the Coinbase level 3 LOB.
  :retry-delay - the delay in milliseconds to wait after an invalid Coinbase LOB
  was received before trying again."
  [{:keys [in out init-delay retry-delay sandbox?]
                        :or   {init-delay  5000
                               retry-delay 3000
                               out         (chan 1)}
                        :as   opt}]
  (go
    (info "waiting for first input msg...")
    (let [cb-level3-ch (chan 1)
          msg1         (<! in)]
      (go
        (info "waiting" init-delay "to request lob...")
        (<! (a/timeout init-delay))
        (info "request delay completed, requesting lob")
        (req-level3 sandbox? cb-level3-ch))
      (go
        (info "collecting input messages..")
        (loop [msgs [msg1]]
          (alt!
            cb-level3-ch
            ([cb-lob]
             (if (>= (:sequence cb-lob) (:sequence msg1))
               (do (info "lob initialized")
                   (->> msgs
                        (drop-while (fn [msg] (< (:sequence msg) (:sequence cb-lob))))
                        (with-msgs (cb-level3->lob cb-lob))
                        (>! out)))
               ;; else, request another lob and try again
               (do
                 (info "lob snapshot occured before first collected feed message, invalid"
                   "waiting to retry...")
                 (<! (a/timeout retry-delay))
                 (req-level3 sandbox? cb-level3-ch)
                 (recur msgs))))
            in
            ([msg] (recur (conj msgs msg))))))))
  out)
```

Now that we have an initial LOB and a channel of messages to add to it, we will batch those messages every 100 milliseconds and update the LOB on each batch. Here's a helper function for this:

```clojure
(defn batched-reductions [batch-ms rf init input-ch & [out]]
  (let [out (or out (chan (a/sliding-buffer 1)))]
    (go
      (loop [acc   init
             batch []
             to    (a/timeout batch-ms)]
        (alt!
          input-ch ([input] (when input (recur acc (conj batch input) to)))
          to ([_]
              (let [new-acc (rf acc batch)]
                (>! out new-acc)
                (recur new-acc [] (a/timeout batch-ms)))))))
    out))
```

## Putting it all together. 

We can now finally get a real time LOB!

```clojure
(def sandbox? false) ;;We're now ready for non-sandbox data
(def ws-prom (a/promise-chan))
(def lob-input-ch (chan 20000 (comp (map parse-ws-msg) (filter lob-msg?))))
(def init-ws-msg (json/write-str
                   {:type        :subscribe
                    :product_ids ["BTC-USD"]
                    :channels    ["full"]}))


(ws->ch (if sandbox? sandbox-websocket-url websocket-url) ws-prom lob-input-ch)
;;(ws/close (<!! ws-prom)) 

(def init-lob (a/promise-chan))
(def lobs (chan (a/sliding-buffer 1)))

(ws/send (<!! ws-prom) init-ws-msg)

(get-initial-lob {:sandbox? sandbox?
                  :in       lob-input-ch
                  :out      init-lob})

;;output:
;;waiting for first input msg...
;;waiting 5000 to request lob...
;;collecting input messages..
;;request delay completed, requesting lob
;;lob initialized


(go (batched-reductions 100
      with-msgs
      (<! init-lob)
      lob-input-ch
      lobs))

(a/poll! lobs)


;;output:
{:dev.jt.lob/asks {30996.99M {#uuid "a5e7..." [#object[java.time.Instant "0x.." "...43:47.866601Z"]
                                               0.70064346M]},
                   30997M    {#uuid "30db..." [#object[java.time.Instant "0x.." "...43:47.727023Z"]
                                               0.05447185M]},
                   30997.01M {#uuid "4283..." [#object[java.time.Instant "0x.." "...43:47.488915Z"]
                                               0.00455823M]},
                   30999.92M {#uuid "b752..." [#object[java.time.Instant "0x.." "...43:47.875504Z"]
                                               0.36M]},
                   ...},
 :dev.jt.lob/bids {31426.38M {},
                   30990.7M  {#uuid "ab4c..." [#object[java.time.Instant "0x.." "...43:48.103257Z"]
                                               0.01388998M]},
                   30990.69M {#uuid "4d57..." [#object[java.time.Instant "0x.." "...43:48.085675Z"]
                                               0.161M]},
                   30990.64M {#uuid "e2d0..." [#object[java.time.Instant "0x.." "...43:47.394468Z"]
                                               0.25M]},
                   ...},
 :sequence        19378091372,
 :time            #object[java.time.Instant "0x..." "...43:48.182824Z"]}

```

This will be updated in real time. Every single order placed on Coinbase pro will be reflected in the latest LOB on the `lobs` channel.

We can make our own aggregated lob as follows:

```
(->> (select-keys (a/poll! lobs) [::lob/asks ::lob/bids])
     (map (fn [[side-key px->levels]]
            [side-key
             (reduce-kv
               (fn [m px level]
                 (assoc m px (lob/level-size level)))
               (empty px->levels)
               px->levels)]))
     (into {})) 
```


## Conclusion

We now have a non-aggregated bitcoin order book. This is cool but it's not the funnest thing to stare at for hours on end. However it is a necessary foundation on which to build things that are more fun, such as visualizations and bots for executing trading strategies. We will explore these in future posts, stay tuned!


### About Me

My name is Justin. If you're interested in Clojure and trading and want to chat feel free to reach out to me at jjttjj@gmail.com. I'm beginning to try to find people with similar interests to collaborate with.


Copyright © 2021 Justin Tirrell
