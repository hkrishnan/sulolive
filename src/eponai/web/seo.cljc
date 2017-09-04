(ns eponai.web.seo
  (:require
    [eponai.common.photos :as photos]
    [eponai.common.database :as db]
    [eponai.common.stream :as stream]
    [eponai.common.routes :as routes]
    [eponai.common.format :as common.format]
    [eponai.client.routes :as client.routes]
    [eponai.common.ui.dom :as dom]
    [eponai.web.header :as header]
    #?(:clj
    [eponai.server.external.host :as host])
    #?(:clj
    [eponai.server.external.wowza :as wowza])
    [eponai.common.shared :as shared]
    [eponai.client.client-env :as client-env]
    [clojure.string :as string]
    [taoensso.timbre :refer [debug]]
    [eponai.common.api.products :as products]))

(defn item-type
  "Return URL for google schema markup type given a keyword.
  The name of the keyword is used and capitalized, so make sure the keyword represents a valid item type in https://schema.org"
  [t]
  (let [schema-domain "https://schema.org/"]
    (str schema-domain (string/capitalize (name t)))))

(defn- server-url [{:keys [system]}]
  (str #?(:clj  (host/webserver-url (:system/server-address system))
          :cljs js/window.location.origin)))

(defn- p-meta-tag
  "Makes it easy to create a meta tag map with property and content."
  [property-key content & [id]]
  (when (some? content)
    (header/meta-tag :property (name property-key)
                     :content content)))
(defn- title-tag
  "Adds the :og:title property to the title for facebook."
  [title]
  (header/title-tag (str title " | SULO Live")))

(defn- description-tag
  "Adds the :og:description property to the title for facebook."
  [description]
  (header/description-tag description :property "og:description"))

(defn- canonical-url-tag [{:keys [route route-params]}]
  (let [href (str "https://sulo.live" (routes/path route route-params))
        type "canonical"]
    (header/link-tag {:id   type
                      :rel  type
                      :href href})))


(defn- wowza-url [{:keys [system reconciler]}]
  #?(:clj  (-> (:system/client-env system)
               (client-env/get-key :wowza-subscriber-url))
     :cljs (-> (shared/by-key reconciler :shared/client-env)
               (client-env/get-key :wowza-subscriber-url))))

(defn- default-data
  "This data is ALWAYS included if not overwritten by other functions."
  [{:keys [db route-map system reconciler]}]
  (let [{:keys [route route-params]} route-map
        title "SULO Live - Buy and sell handmade clothes, jewelry, art, fashion, bath and home decor products."
        description "Buy and sell handmade men's or women's fashion, clothes, jewelry, handbags, shoes, and home decor, beauty, ceramics, art from a marketplace of independent and new brands from Canada."
        image (photos/transform "static/products" :transformation/preview)]
    [
     ;; Title
     (title-tag title)
     (description-tag description)

     ;; Canonical link
     (canonical-url-tag route-map)

     ;; Facebook
     (p-meta-tag :fb:app_id "936364773079066")
     (p-meta-tag :og:title title)
     (p-meta-tag :og:image image)
     (p-meta-tag :og:url (str (server-url {:system system}) (routes/path route route-params)))
     ;(p-meta-tag :og:description description)

     ;; Twitter
     (p-meta-tag :twitter:card "summary_large_image")
     (p-meta-tag :twitter:site "@sulolive")]))

(defmulti head-seo-tags-by-route (fn [k _] k))

(defmethod head-seo-tags-by-route :default
  [_ _]
  [])

(defmethod head-seo-tags-by-route :store
  [_ {:keys [db route-map system reconciler]}]
  (when-let [store (some->> (get-in route-map [:route-params :store-id])
                            (db/store-id->dbid db)
                            (db/entity db))]
    (let [{:keys [store/profile]} store
          image (photos/transform (get-in profile [:store.profile/photo :photo/id]) :transformation/thumbnail)
          stream-url (when (= :stream.state/live (get-in store [:stream/_store :stream/state]))
                       (stream/wowza-live-stream-url (wowza-url {:system system :reconciler reconciler})
                                                     (stream/stream-id store)))
          description-html (common.format/bytes->str (:store.profile/description profile))
          description-text (if description-html
                             (or (not-empty (clojure.string/replace description-html #"<(?:.|\n)*?>" ""))
                                 (:store.profile/name profile))
                             (:store.profile/name profile))]
      [
       ;; Title
       (title-tag (string/join " - " [(:store.profile/name profile) (:store.profile/tagline profile)]))
       (description-tag description-text)


       ;; Facebook
       (p-meta-tag :og:title (:store.profile/name profile))
       (p-meta-tag :og:type "video.other")

       ;; Twitter
       (p-meta-tag :og:image image)
       ;; When video
       ;; Facebook
       (p-meta-tag :og:video stream-url)
       (p-meta-tag :og:video:secure_url stream-url)
       ;; Twitter
       (p-meta-tag :twitter:player:stream stream-url)])))

(defmethod head-seo-tags-by-route :product
  [_ {:keys [route-map db]}]
  (when-let [product (some->> route-map
                              (client.routes/with-normalized-route-params db)
                              :route-params
                              :product-id
                              (db/entity db))]
    (let [product (db/pull db [{:store.item/photos [:store.item.photo/photo :store.item.photo/index]}
                               :store.item/name
                               :store.item/description
                               {:store/_items [{:store/profile [:store.profile/name]}]}]
                           (:db/id product))
          photo (first (sort-by :store.item.photo/index (:store.item/photos product)))
          image (photos/transform (get-in photo [:store.item.photo/photo :photo/id]) :transformation/preview)
          store-name (-> product :store/_items :store/profile :store.profile/name)
          product-name (:store.item/name product)
          description-html (common.format/bytes->str (:store.item/description product))
          description-text (if description-html
                             (or (not-empty (clojure.string/replace description-html #"<(?:.|\n)*?>" ""))
                                 product-name)
                             (str product-name " by " store-name))
          ]
      [
       ;; Title
       (title-tag (str product-name " | by " store-name))
       (description-tag description-text)
       ;; Canonical link


       ;; Facebook
       (when (some? product-name)
         (p-meta-tag :og:title (str product-name " - SULO Live")))
       (p-meta-tag :og:type "product")
       ;(p-meta-tag :og:description description-text)
       (p-meta-tag :og:image image)
       ;; Twitter
       ])))

(defmethod head-seo-tags-by-route :browse/gender
  [_ {:keys [route-map db]}]
  (debug "Route: " route-map)
  (let [{:keys [route-params]} route-map
        gender (string/capitalize (:sub-category route-params))]
    [(title-tag (str gender "'s clothes, accessories, jewelry."))
     (description-tag (str "Shop " gender "'s on SULO Live, a new marketplace that makes it easy to shop handmade from independant Canadian brands and follow their work process."))]))

(defmethod head-seo-tags-by-route :browse/gender+top
  [_ {:keys [route-map db]}]
  (debug "Route: " route-map)
  (let [{:keys [route-params]} route-map
        category (:top-category route-params)
        gender (:sub-category route-params)]
    [(title-tag (str (string/capitalize gender) "'s " category))
     (description-tag (str "Shop " gender "'s " category " on SULO Live, a new marketplace that makes it easy to shop handmade from independant Canadian brands and follow their work process."))]))

(defn head-meta-data
  "Takes db, route-map and system (for clj) or reconciler (for cljs).
  Returns head-meta-data to be processed by eponai.web.header"
  [{:keys [db route-map system reconciler] :as params}]
  (debug "Tag for routemap : " route-map)
  (->> (concat
         (head-seo-tags-by-route (:route route-map) params)
         (default-data params))
       (filter some?)))
