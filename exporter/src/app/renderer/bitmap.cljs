;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.renderer.bitmap
  "A bitmap renderer."
  (:require
   [app.browser :as bw]
   [app.common.data :as d]
   [app.common.exceptions :as ex :include-macros true]
   [app.common.logging :as l]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.config :as cf]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [lambdaisland.uri :as u]
   [promesa.core :as p]))

(defn screenshot-object
  [{:keys [file-id page-id object-id token scale type]}]
  (p/let [path (str "/render-object/" file-id "/" page-id "/" object-id)
          uri  (-> (u/uri (cf/get :public-uri))
                   (assoc :path "/")
                   (assoc :fragment path))]
    (bw/exec!
     #js {:screen #js {:width bw/default-viewport-width
                       :height bw/default-viewport-height}
          :viewport #js {:width bw/default-viewport-width
                         :height bw/default-viewport-height}
          :locale "en-US"
          :storageState #js {:cookies (bw/create-cookies uri {:token token})}
          :deviceScaleFactor scale
          :userAgent bw/default-user-agent}
     (fn [page]
       (l/info :uri uri)
       (p/do!
        (bw/nav! page (str uri))
        (p/let [node (bw/select page "#screenshot")]
          (bw/wait-for node)
          (bw/eval! page (js* "() => document.body.style.background = 'transparent'"))
          (p/let [kaka (bw/eval! page (js* "() => screen.width"))]
            (js/console.log "KKKK" kaka))
          (bw/sleep page 2000)
          (case type
            :png  (bw/screenshot node {:omit-background? true :type type})
            :jpeg (bw/screenshot node {:omit-background? false :type type}))))))))

(s/def ::name ::us/string)
(s/def ::suffix ::us/string)
(s/def ::type #{:jpeg :png})
(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::scale ::us/number)
(s/def ::token ::us/string)
(s/def ::filename ::us/string)

(s/def ::render-params
  (s/keys :req-un [::name ::suffix ::type ::object-id ::page-id ::scale ::token ::file-id]
          :opt-un [::filename]))

(defn render
  [params]
  (us/assert ::render-params params)
  (p/let [content (screenshot-object params)]
    {:content content
     :filename (or (:filename params)
                   (str (:name params)
                        (:suffix params "")
                        (case (:type params)
                          :png ".png"
                          :jpeg ".jpg")))
     :length (alength content)
     :mime-type (case (:type params)
                  :png "image/png"
                  :jpeg "image/jpeg")}))

