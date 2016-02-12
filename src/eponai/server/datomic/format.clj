(ns eponai.server.datomic.format
  (:require [clj-time.core :as t]
            [clj-time.coerce :as c]
            [datomic.api :only [db a] :as d]
            [eponai.common.format :as common.format]
            [eponai.server.datomic.pull :as p]))

(defn user->db-user
  ([]
    (user->db-user nil))
  ([email]
   (cond->
     {:db/id       (d/tempid :db.part/user)
      :user/uuid   (d/squuid)
      :user/status :user.status/new}
     email
     (assoc :user/email email))))

(defn fb-user-db-user
  [user-id access-token user-eid]
  {:db/id         (d/tempid :db.part/user)
   :fb-user/id    user-id
   :fb-user/token access-token
   :fb-user/user  user-eid})

(defn ->db-email-verification
  ([entity status]
    (->db-email-verification entity status nil))
  ([entity status time-limit]
   {:db/id                   (d/tempid :db.part/user)
    :verification/status     status
    :verification/created-at (c/to-long (t/now))
    :verification/uuid       (d/squuid)
    :verification/entity     (:db/id entity)
    :verification/attribute  :user/email
    :verification/value      (:user/email entity)
    :verification/time-limit (or time-limit 15)}))

(defn db-budget [user-eid]
  {:db/id             (d/tempid :db.part/user)
   :budget/uuid       (d/squuid)
   :budget/created-by user-eid
   :budget/created-at (c/to-long (t/now))
   :budget/name       "Default"})

(defn cur-rates->db-txs
  "Returns a vector with datomic entites representing a currency conversions
  for the given date. m should be a  map with timestamp and
  rates of the form {:date \"yyyy-MM-dd\" :rates {:code rate ...}}"
  [data]
  (let [map-fn (fn [[code rate]]
                 {:db/id               (d/tempid :db.part/user)
                  :conversion/date     (common.format/date-str->db-tx (:date data))
                  :conversion/currency [:currency/code (name code)]
                  :conversion/rate     (bigdec rate)})]
    (map map-fn (:rates data))))

(defn curs->db-txs
  "Returns a sequence of currency datomic entries for the given map representing
  currencies of the form {:code \"name\" ...}."
  [currencies]
  (let [map-fn (fn [[c n]] {:db/id         (d/tempid :db.part/user)
                            :currency/code (name c)
                            :currency/name n})]
    (map map-fn currencies)))

(defn cur-infos->db-txs
  [cur-infos]
  (map (fn [info]
         {:db/id                   (d/tempid :db.part/user)
          :currency/code           (:code info)
          :currency/symbol         (:symbol info)
          :currency/symbol-native  (:symbol_native info)
          :currency/decimal-digits (:decimal_digits info)})
       cur-infos))

(defn user-account-map
  "Map representing a full new user account to be transacted into datomic.
  Provide opts to apply when creating account:

  * :verification-time-limit - Time limite (in minutes) that should be used for the email
                               verification before it expires (0 for infinite).
  Returns keys #{:user :verification :budget}."
  ([email]
   (user-account-map email {}))
  ([email opts]
   (let [user (user->db-user email)]
     {:user         user
      :verification (->db-email-verification user :verification.status/pending (:verification-time-limit opts))
      :budget       (db-budget (:db/id user))})))