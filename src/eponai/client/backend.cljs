(ns eponai.client.backend
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [ajax.core :as http]
            [cognitect.transit :as t]
            [cljs.core.async :as async]
            [eponai.client.ui.add_transaction :as add_transaction]))

(def testdata
  {:schema
   [{:db/valueType   :db.type/ref,
     :db/cardinality :db.cardinality/one,
     :db/doc         "Date of the transaction.",
     :db/ident       :transaction/date}
    {:db/unique      :db.unique/identity,
     :db/valueType   :db.type/string,
     :db/cardinality :db.cardinality/one,
     :db/doc         "Three letter currency code, e.g. 'USD'.",
     :db/ident       :currency/code}
    {:db/valueType   :db.type/ref,
     :db/cardinality :db.cardinality/one,
     :db/doc         "Currency of the transaction.",
     :db/ident       :transaction/currency}
    {:db/unique      :db.unique/identity,
     :db/valueType   :db.type/string,
     :db/cardinality :db.cardinality/one,
     :db/doc         "String representation of the date of the form 'yy-MM-dd'.",
     :db/ident       :date/ymd}
    {:db/unique      :db.unique/identity,
     :db/valueType   :db.type/uuid,
     :db/cardinality :db.cardinality/one,
     :db/doc         "Unique identifier for a transaction.",
     :db/ident       :transaction/uuid}
    {:db/unique      :db.unique/identity,
     :db/valueType   :db.type/keyword,
     :db/cardinality :db.cardinality/one,
     :db/doc         "Attribute used to uniquely name an entity.",
     :db/ident       :db/ident}
    {:db/valueType   :db.type/ref,
     :db/cardinality :db.cardinality/many,
     :db/doc         "Attribute used to uniquely name an entity.",
     :db/ident       :transaction/tags}],
   :entities
   [{:transaction/date     {:db/id 17592186045421},
     :transaction/currency {:db/id 17592186045418},
     :transaction/name     "dinner",
     :db/id                17592186045425,
     :transaction/uuid     #uuid "de1ddbd1-883e-4d46-ab89-44da0efd6989",
     :transaction/amount   350}
    {:transaction/date     {:db/id 17592186045421},
     :transaction/currency {:db/id 17592186045418},
     :transaction/name     "lunch",
     :db/id                17592186045420,
     :transaction/uuid     #uuid "bd679ca2-ba83-4366-b8ba-ee7519da2acf",
     :transaction/amount   180}
    {:transaction/date     {:db/id 17592186045421},
     :transaction/currency {:db/id 17592186045418},
     :transaction/name     "coffee",
     :db/id                17592186045423,
     :transaction/uuid     #uuid "75ebb9de-5396-455c-8f4a-0e55d3f65609",
     :transaction/amount   791}
    {:transaction/date     {:db/id 1011},
     :transaction/currency {:db/id 17592186045418},
     :transaction/name     "market",
     :db/id                2001,
     :transaction/uuid     #uuid "75ebb9de-5396-455c-1111-0e55d3f65609",
     :transaction/amount   140}
    {:date/timestamp 1444435200000,
     :date/ymd       "2015-10-10",
     :date/day       10,
     :db/id          17592186045421,
     :date/month     10,
     :date/year      2015}
    {:date/timestamp 1444521600000,
     :date/ymd       "2015-10-11",
     :date/day       11,
     :db/id          1011,
     :date/month     10,
     :date/year      2015}]})

(def test-currencies {:entities [{:currency/name "Thai Baht",
                                  :currency/code "THB",
                                  :db/id         17592186045418}
                                 {:currency/name "Swedish Crown",
                                  :currency/code "SEK",
                                  :db/id         17592186045418}]})

(defn GET
  "Put data on the channel. Put the error-data if there's an error (for testing)."
  [chan endpoint error-data]
  (http/GET endpoint
            {:handler         #(let [res %]
                                (prn {:endpoint endpoint :type (type res) :response res})
                                (async/put! chan res))
             :response-format :transit
             ;; Transit handler to read big-int
             :handlers        {"n" cljs.reader/read-string}

             :error-handler   #(async/put! chan error-data)}))

;; TODO: implement for reals
(defn data-provider []
  (fn [c]
    (let [schema-chan (async/chan)
          txs-chan (async/chan)
          curr-chan (async/chan)]
      (go (let [schema (async/<! schema-chan)
                txs (async/<! txs-chan)
                currencies (async/<! curr-chan)]
            (async/>! c {:schema   schema
                         :entities (concat (:entities txs)
                                           (:entities currencies))})))
      ;; Do the actions
      (GET txs-chan "/user/txs" testdata)
      (GET schema-chan "/schema" (:schema testdata))
      (GET curr-chan "/user/curs" test-currencies))))

(defn act-test [& args] (apply prn "act test with args: " args))
(defmulti act (fn [{:keys [action]} _] action))
(defmethod act ::add_transaction/create-transaction [{:keys [state params]} callback]
  (let [{:keys [transaction tags curs date]} params]
    (prn "FOOOOOOOOOO WE DID IT!1")
    (prn "FOOOOOOOOOO WE DID IT!2")
    (prn "FOOOOOOOOOO WE DID IT!3")
    (prn "FOOOOOOOOOO WE DID IT!4")
    (prn "FOOOOOOOOOO WE DID IT!5")
    (prn "FOOOOOOOOOO WE DID IT!6")
    (prn "FOOOOOOOOOO WE DID IT!7")))
