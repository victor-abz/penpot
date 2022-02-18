;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.shape
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.custom-stroke :as cs]
   [app.main.ui.shapes.export :as ed]
   [app.main.ui.shapes.fills :as fills]
   [app.main.ui.shapes.filters :as filters]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.gradients :as grad]
   [app.main.ui.shapes.svg-defs :as defs]
   [app.util.object :as obj]
   [rumext.alpha :as mf]))

(mf/defc shape-container
  {::mf/forward-ref true
   ::mf/wrap-props false}
  [props ref]
  (let [shape          (obj/get props "shape")
        children       (obj/get props "children")
        pointer-events (obj/get props "pointer-events")

        type           (:type shape)
        render-id      (mf/use-memo #(str (uuid/next)))
        filter-id      (str "filter_" render-id)
        styles         (-> (obj/new)
                           (obj/set! "pointerEvents" pointer-events)

                           (cond-> (and (:blend-mode shape) (not= (:blend-mode shape) :normal))
                             (obj/set! "mixBlendMode" (d/name (:blend-mode shape)))))

        include-metadata? (mf/use-ctx ed/include-metadata-ctx)

        wrapper-props
        (-> (obj/clone props)
            (obj/without ["shape" "children"])
            (obj/set! "ref" ref)
            (obj/set! "id" (str "shape-" (:id shape)))
            (obj/set! "filter" (filters/filter-str filter-id shape))
            (obj/set! "style" styles))

        wrapper-props
        (cond-> wrapper-props
          (= :frame type)
          (obj/set! "clipPath" (frame/frame-clip-url shape render-id))

          (= :group type)
          (attrs/add-style-attrs shape render-id))
        
        {:keys [x y width height]} (:selrect shape)]

    [:& (mf/provider muc/render-ctx) {:value render-id}
     [:> :g wrapper-props
      (when include-metadata?
        [:& ed/export-data {:shape shape}])

      [:defs
       [:& defs/svg-defs          {:shape shape :render-id render-id}]
       [:& filters/filters        {:shape shape :filter-id filter-id}]
       ;; TODO
      ;;  [:& grad/gradient          {:shape shape :attr :stroke-color-gradient}]

       (for [[index value] (-> (d/enumerate (:strokes shape [])) reverse)]
         (cond (some? (:stroke-color-gradient value))
               (case (:type (:stroke-color-gradient value))
                 :linear [:> grad/linear-gradient #js {:id (str (name :stroke-color-gradient) "_" render-id "_" index)
                                                       :gradient (:stroke-color-gradient value)
                                                       :shape shape}]
                 :radial [:> grad/radial-gradient #js {:id (str (name :stroke-color-gradient) "_" render-id "_" index)
                                                       :gradient (:stroke-color-gradient value)
                                                       :shape shape}])))

       (when (or (some? (:fill-image shape))
                 (= :image (:type shape))
                 (> (count (:fills shape)) 1)
                 (some :fill-color-gradient (:fills shape)))
         [:& fills/fills            {:shape shape :render-id render-id}])
      ;;  [:& cs/stroke-defs         {:shape shape :render-id render-id}]
       (for [[index value] (-> (d/enumerate (:strokes shape)) reverse)]
         [:& cs/stroke-defs         {:shape value :render-id render-id :index index}])
       [:& frame/frame-clip-def   {:shape shape :render-id render-id}]]
      children]]))
