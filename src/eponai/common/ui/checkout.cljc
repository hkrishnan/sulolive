(ns eponai.common.ui.checkout
  (:require
    #?(:cljs
       [eponai.common.ui.checkout.stripe :as stripe])
    [eponai.common.ui.dom :as my-dom]
    [om.dom :as dom]
    [om.next :as om :refer [defui]]
    [eponai.common.ui.utils :as utils]
    ;[eponai.client.routes :as routes]
    [eponai.common.ui.navbar :as nav]
    [eponai.common.ui.common :as common]
    [eponai.common.ui.elements.photo :as photo]
    [eponai.common.ui.elements.css :as css]
    [taoensso.timbre :refer [debug]]))

(defn store-element [s]
  (let [{:store/keys [photo] store-name :store/name} s]
    (my-dom/div
      (->> (css/grid-row)
           (css/add-class :expanded)
           (css/add-class :store-container)
           (css/align :center)
           )
      (my-dom/div
        (->> (css/grid-column)
             (css/grid-column-size {:small 3 :medium 2 :large 1}))
        (photo/circle {:src (:photo/path photo)}))
      (my-dom/div
        (->> (css/grid-column)
             (css/grid-column-size {:small 12})
             (css/text-align :center))
        (dom/div nil (dom/p nil (dom/strong #js {:className "store-name"} store-name)))))))

;(defn store-checkout-element [component store cart-items]
;  (dom/div #js {:className "callout transparent cart-checkout-item"}
;    (store-element store)
;    ;(my-dom/div
;    ;  (css/grid-row))
;
;    (map (fn [sku]
;           (let [{:store.item/keys [price photos]
;                  product-id       :db/id
;                  item-name        :store.item/name} (get sku :store.item/_skus)]
;             (my-dom/div
;               (->> (css/grid-row)
;                    (css/add-class :collapse)
;                    (css/align :middle)
;                    (css/add-class :callout)
;                    (css/add-class :transparent)
;                    (css/add-class :item))
;
;               (my-dom/div
;                 (->> (css/grid-column)
;                      (css/grid-column-size {:small 3 :medium 2 :large 1}))
;                 (photo/square
;                   {:src (:photo/path (first photos))}))
;
;               ;(my-dom/div
;               ;  (->> (css/grid-column))
;               ;  (dom/a #js {:className "close-button"} (dom/small nil "x")))
;               (my-dom/div
;                 (->> (css/grid-column)
;                      (css/grid-column-size {:small 8}))
;
;                 (dom/div #js {:className ""}
;                   (dom/a #js {
;                               ;:href      (routes/url :product {:product-id product-id})
;                               :className "name"}
;                          (dom/span nil item-name)))
;                 (dom/div #js {:className ""}
;                   (dom/span nil (:store.item.sku/value sku))))
;
;               (my-dom/div
;                 (->> (css/grid-column)
;                      (css/align :right)
;                      (css/grid-column-size {:small 3 :medium 2 :large 1})
;                      (css/grid-column-offset {:small 3 :large 0}))
;                 (dom/input #js {:type         "number"
;                                 :defaultValue 1}))
;               (my-dom/div
;                 (->> (css/grid-column)
;                      (css/text-align :right)
;                      )
;                 (dom/div #js {:className ""}
;                   (dom/span #js {:className "price"}
;                             (utils/two-decimal-price price)))))))
;         cart-items)))

(defn shipping-element []
  (dom/div nil
    (dom/h2 nil "Shipping")
    (my-dom/div
      (->> (css/add-class ::css/callout))
      (my-dom/div
        (css/grid-row)
        (my-dom/div
          (->> (css/grid-column))
          (dom/label nil "Country")
          (dom/select nil
                      (dom/option #js {:value "ca"} "Canada"))))

      (my-dom/div
        (css/grid-row)
        (my-dom/div
          (->> (css/grid-column))
          (dom/label nil "Full name")
          (dom/input #js {:type "text"}))
        )

      (my-dom/div
        (css/grid-row)
        (my-dom/div
          (->> (css/grid-column)
               (css/grid-column-size {:small 8}))
          (dom/label nil "Address line 1")
          (dom/input #js {:type "text"}))
        (my-dom/div
          (css/grid-column)
          (dom/label nil "Apt/Suite/Other")
          (dom/input #js {:type "text"}))
        )
      (my-dom/div
        (css/grid-row)
        (my-dom/div
          (->> (css/grid-column))
          (dom/label nil "City")
          (dom/input #js {:type "text"}))
        (my-dom/div
          (->> (css/grid-column))
          (dom/label nil "Province")
          (dom/select nil
                      (dom/option {:value "bc"} "British Columbia")))
        (my-dom/div
          (css/grid-column)
          (dom/label nil "Postal code")
          (dom/input #js {:type "text"}))
        ))))

(defn payment-element [component & [{:keys [sources]}]]
  (let [{:keys [payment-error new-card?]} (om/get-state component)]
    (dom/div nil
      (dom/h2 nil "Payment")
      (my-dom/div
        (css/add-class ::css/callout)
        ;(when (not-empty sources)
        ;  (map (fn [source]
        ;         (my-dom/div
        ;           (css/grid-row)
        ;           (my-dom/div
        ;             (->> (css/grid-column))
        ;             (dom/label #js {:htmlFor "card-element"} "Card number")
        ;             (dom/div #js {:id "card-element"})
        ;             (dom/div #js {:id "card-errors"}))))
        ;       sources))
        (my-dom/div
          (css/grid-row)
          (my-dom/div
            (->> (css/grid-column))
            (dom/label #js {:htmlFor "sulo-card-element"
                            :className (when-not new-card? "hide")} "Card")
            (dom/div #js {:id "sulo-card-element" :className (when-not new-card? "hide")})
            (dom/div #js {:id        "card-errors"
                          :className "text-center"}
              (dom/small nil payment-error))))
        (my-dom/div
          (css/grid-row)
          (my-dom/div
            (->> (css/grid-column))
            (dom/a #js {:onClick   #(om/update-state! component assoc :new-card? true)
                        :className "button hollow"} "Add card")))
        ;(my-dom/div
        ;  (css/grid-row)
        ;  (my-dom/div
        ;    (->> (css/grid-column))
        ;    (dom/label nil "Card number")
        ;    (dom/input #js {:type "text"})))
        ;(my-dom/div
        ;  (css/grid-row)
        ;  (my-dom/div
        ;    (->> (css/grid-column))
        ;    (dom/label nil "Expiry month")
        ;    (dom/select nil (map (fn [i]
        ;                           (dom/option #js {:value i} (str (inc i))))
        ;                         (range 12))))
        ;  (my-dom/div
        ;    (->> (css/grid-column))
        ;    (dom/label nil "Expiry year")
        ;    (dom/select nil (map (fn [i]
        ;                           (dom/option #js {:value i} (str (+ 2017 i))))
        ;                         (range 50)))))
        ))))
(defui Checkout
  static om/IQuery
  (query [_]
    [{:proxy/navbar (om/get-query nav/Navbar)}
     {:query/cart [{:cart/items [:db/id
                                 :store.item.sku/uuid
                                 :store.item.sku/value
                                 {:store.item/_skus [:store.item/price
                                                     {:store.item/photos [:photo/path]}
                                                     :store.item/name
                                                     {:store/_items [:db/id
                                                                     :store/name
                                                                     {:store/photo [:photo/path]}]}]}]}]}
     :query/current-route
     {:query/auth [:user/email]}])
  Object
  ;#?(:cljs
  ;   (add-new-card
  ;     [this store]
  ;     (let [{:keys [query/cart query/auth]} (om/props this)
  ;           {:keys [cart/items]} cart
  ;           ;store-items (get (items-by-store items) store)
  ;           ]
  ;       ;(debug "Checkout store: " store)
  ;       ;(debug "Items for store: " items)
  ;       (stripe/open-checkout this (:user/email auth))
  ;       ;(msg/om-transact! this `[(user/checkout ~{:items (map :store.item.sku/uuid store-items)
  ;       ;                                          :store-id (:db/id store)})])
  ;       )))
  ;#?(:cljs
  ;   (componentWillMount
  ;     [this]
  ;     (let [{:keys [checkout-loaded?]} (om/get-state this)]
  ;       (when-not checkout-loaded?
  ;         (stripe/load-checkout (fn []
  ;                                 (om/update-state! this assoc :checkout-loaded? true :is-stripe-loading? false)))))))
  ;#?(:cljs
  ;   (initLocalState
  ;     [_]
  ;     (let [checkout-loaded (stripe/checkout-loaded?)]
  ;       {:checkout-loaded?   checkout-loaded
  ;        :is-stripe-loading? (not checkout-loaded)})))
  #?(:cljs
     (make-payment
       [this]
       (let [{:keys [card]} (om/get-state this)]
         (stripe/create-token card
                              (fn [token]
                                (debug "Got result: " token))
                              (fn [error]
                                (debug "Got error: " error)
                                (om/update-state! this assoc :payment-error (.-message error)))))))
  (componentDidMount [this]
    (debug "Stripe component did mount")
    #?(:cljs
       (let [card (stripe/mount-payment-form {:card-el "#sulo-card-element"})]
         (om/update-state! this assoc :card card))))

  (render [this]
    (let [{:query/keys [current-route cart auth]
           :proxy/keys [navbar]} (om/props this)
          {:keys [route route-params]} current-route
          {:keys [store-id]} route-params

          checkout-items (filter #(= store-id (get-in % [:store.item/_skus :store/_items :db/id])))]
      (common/page-container
        {:navbar navbar :id "sulo-checkout"}
        (my-dom/div
          (->> (css/grid-row)
               (css/align :center)
               (css/add-class :collapse))
          (my-dom/div
            (->> (css/grid-column)
                 (css/grid-column-size {:small 12 :medium 8 :large 8}))
            ;(shipping-element)
            (payment-element this)
            (my-dom/div (css/text-align :right)
                        (dom/a #js {:className "button"
                                    :onClick #(.make-payment this)}
                               "Next"))))))))

(def ->Checkout (om/factory Checkout))