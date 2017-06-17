(ns eponai.server.external.stripe.webhooks
  (:require
    [postal.core :as postal]
    [taoensso.timbre :refer [debug]]
    [eponai.server.external.email :as email]
    [eponai.common.database :as db]
    [eponai.common.format :as f]))

(defmulti handle-account-webhook (fn [_ event] (debug "Webhook event: " (:type event)) (:type event)))
(defmulti handle-connected-webhook (fn [_ event] (:type event)))

(defmethod handle-connected-webhook :default
  [_ event]
  (debug "No handler implemented for connected webhook event " (:type event) ", doing nothing."))

(defmethod handle-account-webhook :default
  [_ event]
  (debug "No handler implemented for account webhook event " (:type event) ", doing nothing."))

(defn send-order-receipt [{:keys [state system]} event]
  (let [charge (get-in event [:data :object])
        order-uuid (f/str->uuid (get-in charge [:metadata :order_uuid]))
        order (db/pull-one-with (db/db state)
                                [:db/id
                                 {:order/items [{:order.item/parent [{:store.item/_skus [:store.item/name
                                                                                         :store.item/price
                                                                                         {:store.item/photos [{:store.item.photo/photo [:photo/id]}]}]}
                                                                     :store.item.sku/variation]}
                                                :order.item/type
                                                :order.item/amount]}
                                 {:order/shipping [:shipping/name
                                                   {:shipping/address [:shipping.address/country
                                                                       :shipping.address/region
                                                                       :shipping.address/postal
                                                                       :shipping.address/locality
                                                                       :shipping.address/street
                                                                       :shipping.address/street2]}]}
                                 {:order/store [:db/id
                                                {:store/profile [:store.profile/name]}]}
                                 {:order/user [:user/email]}]
                                {:where   '[[?e :order/uuid ?uuid]]
                                 :symbols {'?uuid order-uuid}})]
    (email/-send-order-receipt (:system/email system) {:order order :charge charge})))

(defmethod handle-account-webhook "charge.captured"
  [{:keys [state system] :as env} event]
  ;(debug "Will handle captured charged:  " event)
  (send-order-receipt env event))

(defmethod handle-account-webhook "charge.succeeded"
  [{:keys [state system] :as env} event]
  ;(debug "Will handle captured succeeded:  " event)
  (send-order-receipt env event))