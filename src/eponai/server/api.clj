(ns eponai.server.api
  (:require [clojure.core.async :refer [go >! <! chan put!]]
            [compojure.core :refer :all]
            [datomic.api :as d]
            [eponai.common.database.pull :as pull]
            [eponai.server.datomic.transact :as t]
            [eponai.common.database.transact :refer [transact]]
            [eponai.server.datomic.pull :as p]
            [eponai.server.http :as h]
            [eponai.server.external.stripe :as stripe]
            [eponai.server.external.mailchimp :as mailchimp]
            [clj-time.core :as time]
            [clj-time.coerce :as c]
            [eponai.common.format :as f]
            [taoensso.timbre :refer [debug error info]]
            [ring.util.response :as r]
            [environ.core :refer [env]]
            [clj-time.core :as time])
  (:import (datomic.peer LocalConnection)))

; Actions

(defn signin
  "Create a new user and transact into datomic.

  Returns channel with username and db-after user is added to use for email verification."
  [conn email]
  {:pre [(instance? LocalConnection conn)
         (string? email)]}
  (if email
    (let [user (d/entity (d/db conn) (:db/id (p/user (d/db conn) email)))
          email-chan (chan 1)]
      (if user
        (let [verification (t/email-verification conn user :verification.status/pending)]
          (info "New verification " (:verification/uuid verification) "for user:" email)
          (put! email-chan verification)
          {:email-chan email-chan
           :status (:user/status user)})
        (let [verification (t/new-user conn email)]
          (debug "Creating new user with email:" email "verification:" (:verification/uuid verification))
          (put! email-chan verification)
          (debug "Done creating new user with email:" email)
          {:email-chan email-chan
           :status :user.status/new})))
    (throw (ex-info "Trying to signup with nil email."
                    {:cause ::signup-error}))))

(defn verify-email
  "Try and set the verification status if the verification with the specified uuid to :verification.status/verified.

  If more than 15 minutes has passed since the verification entity was created, sets the status to
  :verification.status/expired and throws exception.

  If the verification does not have status :verification.status/pending,
  it means that it's already verified or expired, throws exception.

  On success returns {:db/id some-id} for the user this verification belongs to."
  [conn uuid]
  {:pre [(instance? LocalConnection conn)
         (string? uuid)]}
  (let [ex (fn [msg] (ex-info msg
                              {:cause   ::verification-error
                               :status  ::h/unathorized
                               :data    {:uuid uuid}
                               :message "The verification link is invalid."}))]
    (if-let [verification (p/verification (d/db conn) uuid)]
      (let [verification-time (c/from-long (:verification/created-at verification))
            time-interval (time/in-minutes (time/interval verification-time (time/now)))]
        ; If the verification was not used within 15 minutes, it's expired.
        (if (>= 15 time-interval)
           ;If verification status is not pending, we've already verified this or it's expired.
           (if (= (:verification/status verification)
                  :verification.status/pending)
             (do
               (debug "Successful verify for uuid: " (:verification/uuid verification))
               (t/add conn (:db/id verification) :verification/status :verification.status/verified)
               (:verification/entity verification))
             (throw (ex "Invalid verification UUID")))
           (do
            (t/add conn (:db/id verification) :verification/status :verification.status/expired)
            (throw (ex "Expired verification UUID")))))
      ; If verification does not exist, throw invalid error
      (throw (ex "Verification UUID does not exist")))))

(defn activate-account
  "Try and activate account for the user with UUId user-uuid, and user email passed in.

  Default would be the email from the user's FB account or the one they signed up with. However,
  they might change, and we need to check that it's verified. If it's verified, go ahead and activate the account.

  The user is updated with the email provided when activating the account.

  Throws exception if the email provided is not already verified, email is already in use,
  or the user UUID is not found."
  [conn user-uuid email]
  {:pre [(instance? LocalConnection conn)
         (string? user-uuid)
         (string? email)]}
  (if-let [new-user (p/user (d/db conn) :user/uuid (f/str->uuid user-uuid))]

    (do
      (when-let [existing-user (p/user (d/db conn) email)]
        (if-not (= (:db/id new-user)
                   (:db/id existing-user))
          (throw (ex-info "Email already in use."
                          {:cause ::authentication-error
                           :data  {:email email}}))))

      (let [user-db-id (:db/id new-user)
            new-db (:db-after (t/add conn user-db-id :user/email email))
            verifications (pull/verifications new-db user-db-id :verification.status/verified)]

        ; There's no verification verified for this email on the user,
        ; the user is probably activating their account with a new email.
        ; Create a new verification and throw exception
        (when-not (seq verifications)
          (debug "User not verified for email:" email "will create new verification for user: " user-db-id)
          (let [verification (t/email-verification conn new-user :verification.status/pending)]
            (throw (ex-info "Email not verified."
                            {:cause        ::authentication-error
                             :data         {:email email}
                             :message      "Email not verified"
                             :verification verification}))))

        ; Activate this account and return the user entity
        (let [activated-db (:db-after (transact conn [[:db/add user-db-id :user/status :user.status/active]
                                                      [:db/add user-db-id :user/activated-at (c/to-long (time/now))]]))]
          (debug "Activated account for user-uuid:" user-uuid)
          (p/user activated-db email))))


    ; The user uuid was not found in the database.
    (throw (ex-info "Invalid User id." {:cause   ::authentication-error
                                        :data    {:uuid  user-uuid
                                                  :email email}
                                        :message "No user exists for UUID"}))))

(defn stripe-charge
  "Subscribe user to a plan in Stripe. Basically create a Stripe customer for this user and subscribe to a plan."
  [conn token]
  (try
    (stripe/create-customer conn (env :stripe-secret-key-test) token)
    (catch Exception e
      (throw (ex-info (.getMessage e)
                      {:status ::h/unprocessable-entity
                       :data token})))))

(defn stripe-cancel
  "Cancel the subscription in Stripe for user with uuid."
  [conn user-uuid]
  (try
    ; Find the stripe account for the user.
    (let [stripe-account (pull/pull (d/db conn) [{:stripe/_user [:stripe/customer]}] [:user/uuid user-uuid])]
      (debug "Cancel subscription for user-id: " user-uuid)
      (stripe/cancel-subscription conn
                                  (env :stripe-secret-key-test)
                                  (first (:stripe/_user stripe-account))))
    (catch Exception e
      (throw (ex-info (.getMessage e)
                      {:status ::h/unprocessable-entity
                       :data user-uuid})))))

(defn newsletter-subscribe [conn email]
  (mailchimp/subscribe (env :mail-chimp-api-key) email false))