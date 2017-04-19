(ns eponai.server.api.user
  (:require
    [eponai.common.database :as db]
    [eponai.server.external.stripe :as stripe]
    [taoensso.timbre :refer [debug]]
    [eponai.common.format :as f]))

(defn ->order [state o store-id user-id]
  (let [store (db/lookup-entity (db/db state) store-id)]
    (-> o
        (assoc :order/store store :order/user user-id))))

(defn list-orders [{:keys [state system]} user-id]
  (let [orders (db/pull-all-with (db/db state)
                                 [:db/id :order/store :order/uuid]
                                 {:where   '[[?e :order/user ?u]]
                                  :symbols {'?u user-id}})
        orders-by-store (group-by :order/store orders)
        ;stripe-orders (reduce-kv (fn [l store orders]
        ;                           (let [store-id (:db/id store)
        ;                                 {:keys [stripe/secret]} (stripe/pull-stripe (db/db state) store-id)
        ;                                 order-ids (map :order/id orders)
        ;                                 stripe-orders (stripe/list-orders (:system/stripe system) secret {:ids order-ids})]
        ;                             (apply conj l (map #(->order state % store-id user-id) stripe-orders))))
        ;                         []
        ;                         orders-by-store)
        ]
    orders))

(defn get-order [{:keys [state system]} user-id order-id]
  (let [order (db/pull (db/db state) '[:order/store :order/id] order-id)
        store-id (get-in order [:order/store :db/id])
        {:keys [stripe/secret]} (stripe/pull-stripe (db/db state) store-id)]
    (assoc (->order state
                    (stripe/get-order (:system/stripe system)
                                      secret
                                      (:order/id order))
                    store-id
                    user-id)
      :db/id order-id)))