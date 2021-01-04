(ns lmalob
  (:require [java-http-clj.core :as http]
            [java-http-clj.websocket :as ws]
            [clojure.data.json :as json]
            [clojure.core.async :as a :refer
             [<! <!! >! >!! alt! alts! chan go go-loop poll!]]
            [dev.jt.lob :as lob]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; util

(defn info [& xs] (apply println xs))

(defn uuid [s] (java.util.UUID/fromString s))

(defn cf->ch [^java.util.concurrent.CompletableFuture cf ch]
  (.whenCompleteAsync cf
    (reify
      java.util.function.BiConsumer
      (accept [_ result exception]
        (a/put! ch (or result exception)))))
  ch)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; rest api

(def rest-url "https://api.pro.coinbase.com")
(def sandbox-rest-url "https://api-public.sandbox.pro.coinbase.com")

(defn request-ch [req-map ch]
  (-> (http/send-async req-map)
      (cf->ch ch)))

(defn parse-level3-val [k v]
  (case k
    (:asks :bids) (mapv (fn [[px sz id]] [(bigdec px) (bigdec sz) (uuid id)]) v)
    ;;else return unchanged string
    v))

(defn parse-level3 [s]
  (json/read-str s
    :key-fn keyword
    :value-fn parse-level3-val))

(defn req-level3 [sandbox? ch]
  (let [base   (if sandbox? sandbox-rest-url rest-url)
        result (chan 1 (map (fn [response] (parse-level3 (:body response)))))]
    (request-ch 
      {:method :get
       :uri    (str base "/products/BTC-USD/book?level=3")}
      result)
    (a/pipe result ch)))

(defn cb-level3->lob [{:keys [asks bids sequence] :as cb-lob}]
  (as-> (lob/empty-lob) lob
    (reduce (fn [lob [px sz id time]] (lob/insert lob ::lob/asks px id nil sz)) lob asks)
    (reduce (fn [lob [px sz id time]] (lob/insert lob ::lob/bids px id nil sz)) lob bids)
    (assoc lob :sequence sequence)))

(comment
  (cb-level3->lob (<!! (req-level3 true (a/chan)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; websocket api

(def websocket-url "wss://ws-feed.pro.coinbase.com")
(def sandbox-websocket-url "wss://ws-feed-public.sandbox.pro.coinbase.com")

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

;;; todo: close option
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


(comment

  (def sandbox? false)
  (def ws-prom (a/promise-chan))
  (def lob-input-ch (chan 20000 (comp (map parse-ws-msg) (filter lob-msg?))))
  (def init-ws-msg (json/write-str
                     {:type        :subscribe
                      :product_ids ["BTC-USD"]
                      :channels    ["full"]}))


  (ws->ch (if sandbox? sandbox-websocket-url websocket-url) ws-prom lob-input-ch)

  (def init-lob (a/promise-chan))


  (def lobs (chan (a/sliding-buffer 1)))

  (ws/send (<!! ws-prom) init-ws-msg)

  (get-initial-lob {:sandbox? sandbox?
                    :in       lob-input-ch
                    :out      init-lob})

  (go (batched-reductions 100
        with-msgs
        (<! init-lob)
        lob-input-ch
        lobs))

  ;; to get the latest LOB
  (a/poll! lobs)

  ;; to get an aggregated LOB (price -> total size)
  (->> (select-keys (a/poll! lobs) [::lob/asks ::lob/bids])
       (map (fn [[side-key px->level]]
              [side-key
               (reduce-kv
                 (fn [m px level]
                   (assoc m px (lob/level-size level)))
                 (empty px->level)
                 px->level)]))
       (into {}))

  (ws/close (<!! ws-prom))   ;; close when done

)

