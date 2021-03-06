(ns eponai.web.ui.store.shipping
  (:require
    [clojure.spec.alpha :as s]
    [eponai.common.ui.dom :as dom]
    [eponai.common.ui.elements.css :as css]
    [om.next :as om :refer [defui]]
    [eponai.common.ui.elements.callout :as callout]
    [eponai.common.ui.elements.grid :as grid]
    [eponai.common.ui.components.select :as select]
    [eponai.web.ui.button :as button]
    [eponai.web.ui.switch-input :as switch]
    [eponai.common.ui.common :as common]
    [eponai.common.ui.utils :as ui-utils]
    [taoensso.timbre :refer [debug]]
    #?(:cljs [eponai.web.utils :as utils])
    [eponai.common.ui.elements.menu :as menu]
    [eponai.client.parser.message :as msg]
    [clojure.string :as string]
    [eponai.common.ui.elements.table :as table]
    [eponai.common.ui.elements.input-validate :as v]
    [eponai.common :as c]
    [eponai.common.mixpanel :as mixpanel]
    [eponai.common.format :as f]
    [eponai.client.routes :as routes]
    [eponai.common.database :as db]
    [eponai.web.ui.switch-input :as switch-input]))

(def form-inputs
  {:shipping.rate/title       "shipping.rate.title"
   :shipping.rate/info        "shipping.rate.info"
   :shipping.rate/first       "shipping.rate.first"
   :shipping.rate/additional  "shipping.rate.additional"
   :shipping.rate/offer-free? "shipping.rate.offer-free?"
   :shipping.rate/free-above  "shipping.rate.free-above"

   :shipping.address/street   "shipping.address.street"
   :shipping.address/street2  "shipping.address.street2"
   :shipping.address/locality "shipping.address.locality"
   :shipping.address/postal   "shipping.address.postal"
   :shipping.address/region   "shipping.address.region"
   :shipping.address/country  "shipping.address.country"})

(s/def :shipping.rate/title (s/and string? #(not-empty %)))
(s/def :shipping.rate/title (s/or :value #(string? (not-empty %)) :empty nil?))
(s/def :shipping.rate/first (s/or :pos #(pos? (c/parse-long-safe %)) :zero #(zero? (c/parse-long-safe %))))
(s/def :shipping.rate/additional (s/or :pos #(pos? (c/parse-long-safe %)) :zero #(zero? (c/parse-long-safe %))))
(s/def :shipping.rate/free-above (s/or :value #(and (number? (c/parse-long-safe %)) (<= 0 (c/parse-long-safe %))) :empty nil?))

(s/def :shipping.rule/rate (s/keys :req [:shipping.rate/first :shipping.rate/additional :shipping.rate/title]
                                   :opt [:shipping.rate/free-above]))
(s/def :shipping.rule/rates (s/coll-of :shipping.rule/rate))
(s/def :shipping/rule (s/keys :req [:shipping.rule/rates]))

(s/def ::shipping (s/keys :opt [:shipping/rule]))

(defn validate
  [spec m]
  (when-let [err (s/explain-data spec m)]
    (let [problems (::s/problems err)
          invalid-paths (map (fn [p]
                               (some #(get form-inputs %) p))
                             (map :path problems))]
      {:explain-data  err
       :invalid-paths invalid-paths})))

(defn add-shipping-destination [component selection used-country-codes]
  (let [{:query/keys [countries]} (om/props component)
        countries-by-continent (group-by :country/continent countries)
        {:keys [selected-countries]} (om/get-state component)]
    (debug "Selection: " selection)
    (if-let [selected-continent (some #(when (and (= (:value selection) (:continent/code %))
                                                  (= (:label selection) (:continent/name %))) %) (keys countries-by-continent))]
      (let [new-countries (remove (fn [c]
                                    (or (some #(= (:country/code %)
                                                  (:country/code c))
                                              selected-countries)
                                        (contains? used-country-codes (:country/code c)))) (get countries-by-continent selected-continent))]
        (debug "Selected continent: " selected-continent " with countries: " (into [] (get countries-by-continent selected-continent)))
        (om/update-state! component update :selected-countries into new-countries))
      (do
        (om/update-state! component update :selected-countries conj {:country/code (:value selection) :country/name (:label selection)})
        (debug "Selected country: " selection)))))

(defn remove-country [component country]
  (om/update-state! component update :selected-countries (fn [cs]
                                                           (into [] (remove #(= (:country/code %)
                                                                                (:country/code country))
                                                                            cs)))))

(defn delete-rule-modal [component]
  (let [{:query/keys [store]} (om/props component)
        {:keys [modal-object]} (om/get-state component)
        on-close #(om/update-state! component dissoc :modal :modal-object)
        will-close-store? (not (< 1 (count (get-in store [:store/shipping :shipping/rules]))))]
    (debug "Shipping rules: " (count (get-in store [:store/shipping :shipping/rules])))
    (common/modal
      {:on-close on-close
       :size     "tiny"}
      (dom/h4 (css/add-class :header) "Delete shipping rule")
      (dom/p nil (dom/small nil "Do you want to delete this shipping rule?"))
      (when will-close-store?
        (dom/p nil
               (dom/small nil (dom/strong nil "Note: "))
               (dom/small nil "deleting this rule will close your store. To keep your store open, make sure you have at least one shipping option specified.")))
      (dom/div
        (css/add-class :action-buttons)
        (button/cancel
          {:onClick on-close}
          (dom/span nil "No thanks"))
        (button/cancel
          (->> {:onClick #(do
                           (on-close)
                           (.delete-rule component modal-object))}
               (css/add-class :alert))
          (if will-close-store?
            (dom/span nil "Yes, delete rule and close store")
            (dom/span nil "Yes, delete rule")))))))

(defn shipping-address-modal [component]
  (let [{:query/keys [store countries]} (om/props component)
        shipping (:store/shipping store)                    ;[{:brand "American Express" :last4 1234 :exp-year 2018 :exp-month 4}]
        address (:shipping/address shipping)
        {:keys [input-validation]} (om/get-state component)
        on-close #(do (mixpanel/track "Store: Close shipping info")
                      (om/update-state! component dissoc :modal :input-validation))]
    (common/modal
      (css/add-class :shipping-address-modal {:on-close       on-close
                                              :require-close? true})
      (dom/h4 (css/add-class :header) "Shipping address")
      ;(dom/p nil (dom/small nil "Shipping address to use at checkout."))
      (dom/div
        nil
        ;(dom/label nil "Full name")
        ;(v/input {:id           (:shipping/name form-inputs)
        ;          :type         "text"
        ;          :defaultValue (:shipping/name shipping)
        ;          :name         "name"
        ;          :autoComplete "name"
        ;          :placeholder  ""}
        ;         input-validation)
        (dom/label nil "Country")
        (dom/select
          {:id           (:shipping.address/country form-inputs)
           :name         "ship-country"
           :autoComplete "shipping country"
           :defaultValue (:country/code (:shipping.address/country address) "CA")}
          ;input-validation
          (map (fn [c]
                 (dom/option {:value (:country/code c)} (:country/name c)))
               (sort-by :country/name countries)))

        (dom/label nil "Address")
        (grid/row
          (css/add-class :collapse)
          (grid/column
            (grid/column-size {:small 12 :medium 8})
            (v/input {:id           (:shipping.address/street form-inputs)
                      :type         "text"
                      :defaultValue (:shipping.address/street address)
                      :name         "ship-address"
                      :autoComplete "shipping address-line1"
                      :placeholder  "Street"}
                     input-validation))
          (grid/column
            nil
            (v/input {:id           (:shipping.address/street2 form-inputs)
                      :type         "text"
                      :defaultValue (:shipping.address/street2 address)
                      :placeholder  "Apt/Suite/Other"}
                     input-validation)))
        (grid/row
          (css/add-class :collapse)
          (grid/column
            (grid/column-size {:small 12 :medium 4})
            (v/input {:id           (:shipping.address/postal form-inputs)
                      :type         "text"
                      :defaultValue (:shipping.address/postal address)
                      :name         "ship-zip"
                      :autoComplete "shipping postal-code"
                      :placeholder  "Postal code"}
                     input-validation))
          (grid/column
            (grid/column-size {:small 12 :medium 4})
            (v/input {:id           (:shipping.address/locality form-inputs)
                      :type         "text"
                      :defaultValue (:shipping.address/locality address)
                      :name         "ship-city"
                      :autoComplete "shipping locality"
                      :placeholder  "City"}
                     input-validation))
          (grid/column
            (grid/column-size {:small 12 :medium 4})
            (v/input {:id           (:shipping.address/region form-inputs)
                      :type         "text"
                      :defaultValue (:shipping.address/region address)
                      :name         "ship-state"
                      :autoComplete "shipping region"
                      :placeholder  "Province/State"}
                     input-validation))))
      ;(callout/callout-small
      ;  (css/add-class :warning))
      ;(dom/p nil (dom/small nil "Shipping address cannot be saved yet. We're working on this."))
      (dom/div
        (css/add-class :action-buttons)
        (button/cancel {:onClick on-close})
        (button/save {:onClick #(.save-shipping-address component)})))))

(defn add-shipping-rule-modal [component]
  (let [on-close #(om/update-state! component (fn [st]
                                                (-> st
                                                    (dissoc :modal :shipping-rule/edit-rule :input-validation :shipping-rule/edit-rate)
                                                    (assoc :selected-countries []
                                                           :shipping-rule/section :shipping-rule.section/destinations))))
        {:query/keys [countries store]} (om/props component)
        countries-by-continent (group-by :country/continent countries)
        used-countries (reduce #(into %1 (:shipping.rule/destinations %2)) [] (get-in store [:store/shipping :shipping/rules]))
        {:keys               [input-validation selected-countries modal]
         :shipping-rule/keys [section offer-free? edit-rule edit-rate]} (om/get-state component)
        used-country-codes (set (map :country/code used-countries))]
    (common/modal
      (css/add-class :shipping-rules-modal {:on-close       on-close
                                            :require-close? true})
      (dom/div
        (css/add-class :section-title)
        (cond (and (= section :shipping-rule.section/destinations)
                   (not= modal :modal/add-shipping-rate))
              (dom/h4 (css/add-class :header) (dom/span nil "Add destinations"))
              (or (= section :shipping-rule.section/rates)
                  (= modal :modal/add-shipping-rate))
              (dom/h4 (css/add-class :header) (dom/span nil "Add shipping rate"))))

      (dom/div
        (css/add-classes [:section-container (name (or section ""))])

        (dom/div
          (cond->> (css/add-classes [:section-content :section-content--destinations])
                   (and (= section :shipping-rule.section/destinations)
                        (not= modal :modal/add-shipping-rate))
                   (css/add-class :is-active))
          (dom/p nil (dom/small nil "Select which countries you want to ship to. You'll add rates in the next step.")
                 (dom/br nil)
                 (dom/small nil (dom/strong nil "Tip: ") (dom/span nil "Select entire continents to add all their countries not already in a rule.")))
          (select/->SelectOne (om/computed {:options     (reduce (fn [l [con cs]]
                                                                   (into l (into [{:value     (:continent/code con)
                                                                                   :label     (:continent/name con)
                                                                                   :className "continent"}]
                                                                                 (map (fn [c]
                                                                                        {:value     (:country/code c)
                                                                                         :label     (:country/name c)
                                                                                         :className "country"
                                                                                         :disabled  (or (some #(= (:country/code %)
                                                                                                                  (:country/code c))
                                                                                                              selected-countries)
                                                                                                        (contains? used-country-codes (:country/code c)))})
                                                                                      (sort-by :country/name cs)))))
                                                                 []
                                                                 countries-by-continent)
                                            :placeholder "Select destinasions (e.g. Canada, Europe)"}
                                           {:on-change #(add-shipping-destination component % used-country-codes)}))
          (if (not-empty selected-countries)
            (menu/vertical
              nil
              (map
                (fn [c]
                  (menu/item
                    nil
                    (dom/span nil (:country/name c))
                    (dom/a
                      (->> {:onClick #(remove-country component c)}
                           (css/add-classes [:icon :icon-delete])))))
                (sort-by :country/name selected-countries))))
          (dom/div
            (css/add-class :action-buttons)
            (button/cancel
              {:onClick on-close}
              (dom/span nil "Cancel"))
            (button/save
              (cond->> {:onClick #(om/update-state! component assoc :shipping-rule/section :shipping-rule.section/rates)}
                       (empty? selected-countries)
                       (css/add-class :disabled))
              (dom/span nil "Next"))))

        (dom/div
          (cond->> (css/add-classes [:section-content :section-content--rates])
                   (or (= section :shipping-rule.section/rates)
                       (= modal :modal/add-shipping-rate))
                   (css/add-class :is-active))
          (grid/row
            (css/add-class :collapse)
            (grid/column
              nil
              (dom/label nil "Title")
              (v/input {:type         "text"
                        :id           (:shipping.rate/title form-inputs)
                        :placeholder  "e.g USPS Priority, FedEx 3-day"
                        :defaultValue (:shipping.rate/title edit-rate)}
                       input-validation)
              (dom/small nil "This will be visible to your customers at checkout.")))

          (grid/row
            (css/add-class :collapse)
            (grid/column
              nil
              (dom/label nil "Additional information")
              (v/input {:type         "text"
                        :id           (:shipping.rate/info form-inputs)
                        :defaultValue (:shipping.rate/info edit-rate)}
                       input-validation)
              (dom/small nil "What should shoppers know about this shipping option?")))

          (grid/row
            ;(css/add-class :collapse)
            nil
            (grid/column
              nil
              (dom/label nil "Rate first item")
              (v/input {:type         "number"
                        :id           (:shipping.rate/first form-inputs)
                        :defaultValue (:shipping.rate/first edit-rate 0)
                        :min          0}
                       input-validation))
            (grid/column
              nil
              (dom/label nil "Rate additional item")
              (v/input {:type         "number"
                        :id           (:shipping.rate/additional form-inputs)
                        :defaultValue (:shipping.rate/additional edit-rate 0)
                        :min          0}
                       input-validation)))

          ;(dom/p (css/add-class :header) (dom/span nil "Offers"))

          (dom/label nil "Offer free shipping")
          (switch/switch
            (css/add-class :tiny {:onClick #(om/update-state! component assoc :shipping-rule/offer-free? (.-checked (.-target %)))
                                  :id      (:shipping.rate/offer-free? form-inputs)
                                  :title   "Offer free shipping"}))
          ;(dom/div
          ;  (css/add-class :switch-container)
          ;  (dom/div
          ;    (css/add-classes [:switch :tiny])
          ;    (dom/input {:classes ["switch-input"]
          ;                :id      (:shipping.rate/offer-free? form-inputs)
          ;                :type    "checkbox"
          ;                :name    "free-shipping-switch"
          ;                :onClick #(om/update-state! component assoc :shipping-rule/offer-free? (.-checked (.-target %)))})
          ;    (dom/label
          ;      (css/add-class :switch-paddle {:htmlFor (:shipping.rate/offer-free? form-inputs)})
          ;      (dom/span (css/show-for-sr) "Offer free shipping")))
          ;  (dom/label nil "Offer free shipping"))
          (when offer-free?
            (grid/row
              nil
              (grid/column
                nil
                (dom/label nil "When cart total is above amount")
                (v/input {:type         "number"
                          :id           (:shipping.rate/free-above form-inputs)
                          :defaultValue 0
                          :min          0}
                         input-validation))
              (grid/column nil)))
          (dom/div
            (css/add-class :action-buttons-container)
            (dom/div
              (css/add-class :action-buttons)
              (if-not (= modal :modal/add-shipping-rate)
                (button/default-hollow
                  (button/small {:onClick #(om/update-state! component assoc :shipping-rule/section :shipping-rule.section/destinations)})
                  (dom/i {:classes ["fa fa-chevron-left  fa-fw"]})
                  (dom/span nil "Back"))))
            (dom/div
              (css/add-class :action-buttons)
              (button/cancel
                {:onClick on-close})
              (button/save
                {:onClick #(cond (= modal :modal/add-shipping-rule)
                                 (.save-shipping-rule component)
                                 (= modal :modal/add-shipping-rate)
                                 (.save-shipping-rate component))}))))))))

(defn pickup-rule [store]
  (some #(when (:shipping.rule/pickup? %) %) (get-in store [:store/shipping :shipping/rules])))

(defui StoreShipping
  static om/IQuery
  (query [_]
    [:query/current-route
     :query/messages
     {:query/countries [:country/code
                        :country/name
                        {:country/continent [:continent/code
                                             :continent/name]}]}
     {:query/store [{:store/shipping [{:shipping/rules [:db/id
                                                        ;:shipping.rule/pickup?
                                                        :shipping.rule/title
                                                        {:shipping.rule/rates [:db/id
                                                                               :shipping.rate/title
                                                                               :shipping.rate/info
                                                                               :shipping.rate/first
                                                                               :shipping.rate/additional
                                                                               :shipping.rate/free-above]}
                                                        {:shipping.rule/destinations [:country/code :country/name]}]}
                                      {:shipping/address [:shipping.address/street
                                                          :shipping.address/street2
                                                          :shipping.address/postal
                                                          :shipping.address/locality
                                                          :shipping.address/region
                                                          {:shipping.address/country [:country/code]}]}]}]}])
  Object
  (initLocalState [this]
    {:selected-countries    []
     :shipping-rule/section :shipping-rule.section/destinations})
  (update-free-pickup [this allow-pickup?]
    #?(:cljs
       (let [{:query/keys [store]} (om/props this)
             shipping-rules (get-in store [:store/shipping :shipping/rules])
             store-country (get-in store [:store/shipping :shipping/address :shipping.address/country])
             ]
         (debug "Shipping rules: " shipping-rules)
         (if allow-pickup?
           (when (some? store-country)
             (msg/om-transact! this [(list 'store/save-shipping-rule {:shipping-rule {:shipping.rule/pickup?      true
                                                                                      :shipping.rule/destinations [store-country]
                                                                                      :shipping.rule/rates [{:shipping.rate/title "Free pickup"}]}
                                                                      :store-id      (:db/id store)})
                                     :query/store]))
           (when-let [delete-rule (pickup-rule store)]
             (.delete-rule this delete-rule))))))

  (save-shipping-rule [this]
    #?(:cljs
       (let [rate-first (utils/input-value-by-id (:shipping.rate/first form-inputs))
             rate-additional (utils/input-value-by-id (:shipping.rate/additional form-inputs))
             offer-free? (utils/input-checked-by-id? (:shipping.rate/offer-free? form-inputs))
             free-above (when offer-free? (utils/input-value-by-id (:shipping.rate/free-above form-inputs)))

             rate-title (utils/input-value-by-id (:shipping.rate/title form-inputs))
             rate-info (utils/input-value-or-nil-by-id (:shipping.rate/info form-inputs))
             {:query/keys [current-route store]} (om/props this)
             {:keys               [selected-countries]
              :shipping-rule/keys [edit-rate]} (om/get-state this)

             input-map {:shipping.rule/destinations selected-countries
                        :shipping.rule/rates        [{:shipping.rate/first      rate-first
                                                      :shipping.rate/title      rate-title
                                                      :shipping.rate/info       rate-info
                                                      :shipping.rate/additional rate-additional
                                                      :shipping.rate/free-above free-above}]}
             input-validation (validate :shipping/rule input-map)]
         (when (nil? input-validation)
           (mixpanel/track "Store: Save shipping rule")
           (msg/om-transact! this [(list 'store/save-shipping-rule {:shipping-rule input-map
                                                                    :store-id      (db/store-id->dbid this (:store-id (:route-params current-route)))})
                                   :query/store]))
         (om/update-state! this assoc :input-validation input-validation))))

  (save-shipping-address [this]
    #?(:cljs
       (let [street (utils/input-value-by-id (:shipping.address/street form-inputs))
             street2 (utils/input-value-or-nil-by-id (:shipping.address/street2 form-inputs))
             locality (utils/input-value-by-id (:shipping.address/locality form-inputs))
             postal (utils/input-value-by-id (:shipping.address/postal form-inputs))
             region (utils/input-value-or-nil-by-id (:shipping.address/region form-inputs))
             country (utils/input-value-by-id (:shipping.address/country form-inputs))


             ;rate-title (utils/input-value-by-id (:shipping.rate/title form-inputs))
             ;rate-info (utils/input-value-or-nil-by-id (:shipping.rate/info form-inputs))
             {:query/keys [current-route store]} (om/props this)
             {:keys [selected-countries]} (om/get-state this)

             input-map {:shipping/address (f/remove-nil-keys
                                            {:shipping.address/street   street
                                             :shipping.address/street2  street2
                                             :shipping.address/locality locality
                                             :shipping.address/postal   postal
                                             :shipping.address/region   region
                                             :shipping.address/country  {:country/code country}})}
             input-validation (v/validate ::shipping input-map form-inputs)]
         (debug "Validation map: " input-map)
         (debug "validation: " input-validation)
         (when (nil? input-validation)
           (mixpanel/track "Store: Save shipping from address")
           (msg/om-transact! this [(list 'store/update-shipping {:shipping input-map
                                                                 :store-id (db/store-id->dbid this (:store-id (:route-params current-route)))})
                                   :query/store]))
         (om/update-state! this assoc :input-validation input-validation))))
  (save-shipping-rate [this]
    #?(:cljs
       (let [rate-first (utils/input-value-by-id (:shipping.rate/first form-inputs))
             rate-additional (utils/input-value-by-id (:shipping.rate/additional form-inputs))
             offer-free? (utils/input-checked-by-id? (:shipping.rate/offer-free? form-inputs))
             free-above (when offer-free? (utils/input-value-by-id (:shipping.rate/free-above form-inputs)))

             rule-title (utils/input-value-by-id (:shipping.rate/title form-inputs))
             rate-info (utils/input-value-or-nil-by-id (:shipping.rate/info form-inputs))
             {:query/keys [current-route store]} (om/props this)
             {:shipping-rule/keys [edit-rule]} (om/get-state this)
             input-map {:shipping.rate/first      rate-first
                        :shipping.rate/title      rule-title
                        :shipping.rate/info       rate-info
                        :shipping.rate/additional rate-additional
                        :shipping.rate/free-above free-above}

             input-validation (validate :shipping.rule/rate input-map)]
         (when (nil? input-validation)
           (mixpanel/track "Store: Save shipping rate")
           (msg/om-transact! this [(list 'store/update-shipping-rule {:shipping-rule (update edit-rule :shipping.rule/rates conj input-map)
                                                                      :store-id      (db/store-id->dbid this (:store-id (:route-params current-route)))})
                                   :query/store]))
         (om/update-state! this assoc :input-validation input-validation))))

  (delete-rule [this rule]
    (let [{:query/keys [store]} (om/props this)]
      (msg/om-transact! this [(list 'store/delete-shipping-rule {:rule     rule
                                                                 :store-id (:db/id store)})
                              :query/store])))

  (delete-rate [this rule rate]
    (let [{:query/keys [store current-route]} (om/props this)
          ;{:shipping-rule/keys [edit-rule]} (om/get-state this)
          remove-rate-fn (fn [rates]
                           (remove #(= (:db/id %) (:db/id rate)) rates))]
      (debug "Edit rule: " rule)
      (debug "Delete rate: " rate)
      (msg/om-transact! this (cond-> [(list 'store/update-shipping-rule {:shipping-rule (update rule :shipping.rule/rates remove-rate-fn)
                                                                         :store-id      (db/store-id->dbid this (:store-id (:route-params current-route)))})]
                                     (= 1 (count (:shipping.rule/rates rule)))
                                     (conj (list 'store/delete-shipping-rule {:rule     rule
                                                                              :store-id (:db/id store)}))
                                     :always
                                     (conj :query/store)))))

  (componentDidMount [this]
    #?(:cljs
       (let [{:query/keys [store]} (om/props this)]
         (when-let [switch-element (utils/element-by-id "sulo-pickup")]
           (set! (.-checked switch-element) (some? (pickup-rule store)))))))

  (componentDidUpdate [this _ _]
    (let [rule-message (msg/last-message this 'store/save-shipping-rule)
          update-message (msg/last-message this 'store/update-shipping-rule)
          shipping-msg (msg/last-message this 'store/update-shipping)]
      (when (and (msg/final? rule-message)
                 (msg/success? rule-message))
        (msg/clear-one-message! this 'store/save-shipping-rule)
        (om/update-state! this (fn [st]
                                 (-> st
                                     (dissoc :modal)
                                     (assoc :selected-countries []
                                            :shipping-rule/section :shipping-rule.section/destinations)))))
      (when (and (msg/final? update-message)
                 (msg/success? update-message))
        (msg/clear-one-message! this 'store/update-shipping-rule)
        (om/update-state! this (fn [st]
                                 (-> st
                                     (dissoc :modal :shipping-rule/edit-rule)
                                     (assoc :selected-countries []
                                            :shipping-rule/section :shipping-rule.section/destinations)))))

      (when (and (msg/final? shipping-msg)
                 (msg/success? shipping-msg))
        (msg/clear-one-message! this 'store/update-shipping)
        (om/update-state! this dissoc :modal))))
  (render [this]
    (let [{:keys [modal selected-countries]} (om/get-state this)
          {:query/keys [store]} (om/props this)
          {:store/keys [shipping]} store]

      (dom/div
        {:id "sulo-shipping-settings"}
        (cond (= modal :modal/add-shipping-rule)
              (add-shipping-rule-modal this)
              (= modal :modal/add-shipping-rate)
              (add-shipping-rule-modal this)
              (= modal :modal/add-shipping-address)
              (shipping-address-modal this)
              (= modal :modal/delete-rule)
              (delete-rule-modal this))
        (dom/div
          (css/add-class :section-title)
          (dom/h1 nil "Shipping"))
        ;(dom/div
        ;  (css/add-class :section-title)
        ;  (dom/h2 nil "Shipping settings"))
        ;(callout/callout
        ;  (css/add-classes [:section-container :section-container--shipping-address])
        ;  (menu/vertical
        ;    (css/add-class :section-list)
        ;
        ;    (let [{:shipping/keys [address]
        ;           shipping-name  :shipping/name} shipping
        ;          {:shipping.address/keys [street street2 locality postal region country]} address]
        ;      (menu/item
        ;        nil
        ;        (grid/row
        ;          (->> (css/add-class :collapse)
        ;               (css/align :middle))
        ;          (grid/column
        ;            (grid/column-size {:small 12 :medium 6})
        ;            (dom/label nil "Shipping address")
        ;            (dom/p nil (dom/small nil "This is the address from which your products will ship.")))
        ;          (grid/column
        ;            (css/text-align :right)
        ;
        ;            (if (some? address)
        ;              (common/render-shipping shipping nil)
        ;              ;(dom/p nil
        ;              ;       (dom/span nil shipping-name)
        ;              ;       (dom/br nil)
        ;              ;       (dom/small nil street)
        ;              ;       (dom/br nil)
        ;              ;       (dom/small nil (string/join ", " [locality postal region country])))
        ;              (dom/p nil (dom/small nil (dom/i nil "No saved address"))))
        ;            (button/store-setting-secondary
        ;              {:onClick #(om/update-state! this assoc :modal :modal/add-shipping-address)}
        ;              (dom/span nil "Edit shipping address"))))))
        ;    (menu/item
        ;      nil
        ;      (grid/row
        ;        (->> (css/add-class :collapse)
        ;             (css/align :middle))
        ;        (grid/column
        ;          (grid/column-size {:small 12 :medium 6})
        ;          (dom/label nil "Allow free pickup")
        ;          (dom/p nil (dom/small nil "Allow your customers to pick up their items from your specified shipping address.")))
        ;        (grid/column
        ;          (css/text-align :right)
        ;          (switch-input/switch
        ;            (cond-> {:title    "Allow free pickup"
        ;                     :id       "sulo-pickup"
        ;                     :classes  [:sulo-dark]
        ;                     :onChange #(.update-free-pickup this (.-checked (.-target %)))}
        ;                    (nil? shipping)
        ;                    (assoc :disabled true))
        ;            (dom/span (css/add-class :switch-inactive) "No")
        ;            (dom/span (css/add-class :switch-active) "Yes"))
        ;          (when (nil? shipping)
        ;            (dom/small nil "Specify a shipping address to enable free pickup")))))))


        (dom/div
          (css/add-class :content-section)
          (dom/div
            (css/add-class :section-title)
            (dom/h2 nil "Shipping rules")
            (dom/div
              (css/add-class :subtitle)
              (dom/p nil
                     (dom/small nil "Create shipping rules to define where you will ship your products and shipping rates for these orders. ")
                     (dom/a {:href    (routes/url :help/shipping-rules)
                             :target  "_blank"
                             :onClick #(mixpanel/track "Store: See help for shipping rules")} (dom/small nil "Learn more")))
              (button/store-navigation-default
                {:onClick #(om/update-state! this assoc :modal :modal/add-shipping-rule)}
                (dom/span nil "Add shipping rule"))))
          ;(grid/row
          ;  nil
          ;  (grid/column
          ;    nil
          ;    (dom/p nil
          ;           (dom/small nil "Create shipping rules to define where you will ship your products and shipping rates for these orders. ")
          ;           (dom/a nil (dom/span nil "Learn more")))))
          )
        ;(callout/callout
        ;  (css/add-classes [:section-container :section-container--shipping-rules]))
        ;(grid/row
        ;  (css/add-class :collapse)
        ;  (grid/column
        ;    nil
        ;    (dom/p nil
        ;           (dom/span nil "Create shipping rules to define where you will ship your products and shipping rates for these orders. ")
        ;           (dom/a nil (dom/span nil "Learn more"))))
        ;  (grid/column
        ;    (->> (css/text-align :right)
        ;         (grid/column-size {:small 12 :medium 4}))
        ;    (button/user-setting-default
        ;      {:onClick #(om/update-state! this assoc :modal :modal/add-shipping-rule)}
        ;      (dom/span nil "Add shipping rule"))))

        (map-indexed
          (fn [i sr]
            (let [{:shipping.rule/keys [destinations rates]} sr
                  sorted-dest (sort-by :country/name destinations)
                  show-locations 5]
              (callout/callout
                (css/add-classes [:section-container :section-container--shipping-rules])

                (menu/vertical
                  (css/add-class :section-list)
                  (menu/item
                    nil
                    (dom/div
                      (css/add-class :shipping-rule-card)
                      (dom/div
                        (css/add-classes [:shipping-rule-card-header])
                        (dom/p nil
                               (dom/label nil
                                          (str (string/join ", " (map :country/name (take show-locations sorted-dest)))
                                               (when (< 0 (- (count destinations) show-locations))
                                                 (str " & " (- (count destinations) show-locations) " more")))))
                        ;(dom/a
                        ;  (->> {:onClick #(om/update-state! this assoc :modal :modal/delete-rule :modal-object sr)}
                        ;       (css/add-class :edit-menu))
                        ;  (dom/small nil "delete rule"))
                        )
                      (table/table
                        nil
                        (table/thead
                          nil
                          (table/thead-row
                            nil
                            (table/th nil "Name")
                            (table/th nil "Rate")
                            (table/th nil "Free shipping")
                            (table/th nil "")))
                        (table/tbody
                          nil
                          (map (fn [r]
                                 (table/tbody-row
                                   nil
                                   (table/td nil (dom/span nil (:shipping.rate/title r)))
                                   (table/td
                                     nil
                                     (dom/span nil (ui-utils/two-decimal-price (:shipping.rate/first r)))
                                     (when (< 0 (:shipping.rate/additional r))
                                       (dom/small nil (str " (" (ui-utils/two-decimal-price (:shipping.rate/additional r)) ")"))))
                                   (table/td
                                     nil
                                     (let [{:shipping.rate/keys [free-above additional]
                                            first-rate          :shipping.rate/first} r]
                                       (cond (zero? (+ first-rate additional))
                                             (dom/span nil "Yes")

                                             (some? free-above)
                                             (dom/span nil (str "> " (ui-utils/two-decimal-price free-above)))

                                             :else
                                             (dom/span nil "No")
                                             )))
                                   (table/td
                                     (css/text-align :right)
                                     (dom/a
                                       {:onClick #(if (< 1 (count rates))
                                                   (.delete-rate this sr r)
                                                   (om/update-state! this assoc :modal :modal/delete-rule :modal-object sr))}
                                       (dom/small nil "delete"))))) rates)))
                      (dom/div
                        (css/add-classes [:action-buttons :shipping-rule-card-footer])

                        ;(button/default-hollow
                        ;  (button/small {:onClick #(om/update-state! this assoc :modal :modal/delete-rule :modal-object sr)})
                        ;  (dom/span nil "Delete rule"))
                        (button/store-setting-default
                          (button/small {:onClick #(om/update-state! this assoc :modal :modal/add-shipping-rate :shipping-rule/edit-rule sr)})
                          (dom/span nil "Add shipping rate"))
                        (button/delete
                          (->> {:onClick #(om/update-state! this assoc :modal :modal/delete-rule :modal-object sr)}
                               (css/add-class :alert))
                          (dom/span nil "Delete rule")))))))))
          (remove #(:shipping.rule/pickup? %) (get-in store [:store/shipping :shipping/rules])))
        ;(grid/row
        ;  nil
        ;  (grid/column
        ;    nil
        ;    (button/user-setting-default
        ;      {:onClick #(om/update-state! this assoc :modal :modal/add-shipping-rule)}
        ;      (dom/span nil "Add shipping rule"))))
        ))))
