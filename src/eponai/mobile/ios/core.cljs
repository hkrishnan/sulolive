(ns eponai.mobile.ios.core
  (:require [eponai.mobile.react-helper]                       ;; require this first!
            [eponai.client.utils :as utils]
            [eponai.mobile.ios.app :as app]))

(defn init []
  (utils/install-app)
  (app/run))