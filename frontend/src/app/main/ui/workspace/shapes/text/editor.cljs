;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.text.editor
  (:require
   ["draft-js" :as draft]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.text :as txt]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.texts :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.cursors :as cur]
   [app.main.ui.hooks.mutable-observer :refer [use-mutable-observer]]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.text.styles :as sts]
   [app.main.ui.shapes.text.svg-text :as svg]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.text-editor :as ted]
   [app.util.text-svg-position :as utp]
   [goog.events :as events]
   [rumext.alpha :as mf])
  (:import
   goog.events.EventType))

;; --- Text Editor Rendering

(mf/defc block-component
  {::mf/wrap-props false}
  [props]
  (let [bprops (obj/get props "blockProps")
        data   (obj/get bprops "data")
        style  (sts/generate-paragraph-styles (obj/get bprops "shape")
                                              (obj/get bprops "data"))
        dir    (:text-direction data "auto")]


    [:div {:style style :dir dir}
     [:> draft/EditorBlock props]]))

(mf/defc selection-component
  {::mf/wrap-props false}
  [props]
  (let [children (obj/get props "children")]
    [:span {:style {:background "#ccc" :display "inline-block"}} children]))

(defn render-block
  [block shape]
  (let [type (ted/get-editor-block-type block)]
    (case type
      "unstyled"
      #js {:editable true
           :component block-component
           :props #js {:data (ted/get-editor-block-data block)
                       :shape shape}}
      nil)))

(defn styles-fn [styles content]
  (if (= (.getText content) "")
    (-> (.getData content)
        (.toJS)
        (js->clj :keywordize-keys true)
        (sts/generate-text-styles))
    (-> (txt/styles-to-attrs styles)
        (sts/generate-text-styles))))

(def default-decorator
  (ted/create-decorator "PENPOT_SELECTION" selection-component))

(def empty-editor-state
  (ted/create-editor-state nil default-decorator))

(defn get-blocks-to-setup [block-changes]
  (->> block-changes
       (filter (fn [[_ v]]
                 (nil? (:old v))))
       (mapv first)))

(defn get-blocks-to-add-styles
  [block-changes]
  (->> block-changes
       (filter (fn [[_ v]]
                 (and (not= (:old v) (:new v)) (= (:old v) ""))))
       (mapv first)))

(mf/defc text-shape-edit-html
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false
   ::mf/forward-ref true}
  [props _]
  (let [{:keys [id content] :as shape} (obj/get props "shape")

        state-map     (mf/deref refs/workspace-editor-state)
        state         (get state-map id empty-editor-state)
        self-ref      (mf/use-ref)

        blurred        (mf/use-var false)

        on-key-up
        (fn [event]
          (dom/stop-propagation event)
          (when (kbd/esc? event)
            (st/emit! :interrupt)
            (st/emit! dw/clear-edition-mode)))

        on-mount
        (fn []
          (let [keys [(events/listen js/document EventType.KEYUP on-key-up)]]
            (st/emit! (dwt/initialize-editor-state shape default-decorator)
                      (dwt/select-all shape))
            #(do
               (st/emit! ::dwt/finalize-editor-state)
               (doseq [key keys]
                 (events/unlistenByKey key)))))

        on-blur
        (mf/use-callback
         (mf/deps shape state)
         (fn [event]
           (dom/stop-propagation event)
           (dom/prevent-default event)
           (reset! blurred true)))

        on-focus
        (mf/use-callback
         (mf/deps shape state)
         (fn [_]
           (reset! blurred false)))

        prev-value (mf/use-ref state)

        ;; Effect that keeps updated the `prev-value` reference
        _ (mf/use-effect
           (mf/deps state)
           #(mf/set-ref-val! prev-value state))

        handle-change
        (mf/use-callback
         (fn [state]
           (let [old-state (mf/ref-val prev-value)]
             (if (and (some? state) (some? old-state))
               (let [block-changes (ted/get-content-changes old-state state)
                     prev-data (ted/get-editor-current-inline-styles old-state)
                     block-to-setup (get-blocks-to-setup block-changes)
                     block-to-add-styles (get-blocks-to-add-styles block-changes)]
                 (-> state
                     (ted/setup-block-styles block-to-setup prev-data)
                     (ted/apply-block-styles-to-content block-to-add-styles)))
               state))))

        on-change
        (mf/use-callback
         (fn [val]
           (let [prev-val (mf/ref-val prev-value)
                 styleOverride (ted/get-style-override prev-val)

                 ;; If the content and the selection are the same we keep the style override
                 keep-style? (and (some? styleOverride)
                                  (ted/content-equals prev-val val)
                                  (ted/selection-equals prev-val val))

                 val (cond-> (handle-change val)
                       @blurred
                       (ted/add-editor-blur-selection)

                       (not @blurred)
                       (ted/remove-editor-blur-selection)

                       keep-style?
                       (ted/set-style-override styleOverride))]
             (st/emit! (dwt/update-editor-state shape val)))))

        on-editor
        (mf/use-callback
         (fn [editor]
           (st/emit! (dwt/update-editor editor))
           (when editor
             (.focus ^js editor))))

        handle-return
        (mf/use-callback
         (fn [_ state]
           (let [style (ted/get-editor-current-block-data state)
                 state (-> (ted/insert-text state "\n" style)
                           (handle-change))]
             (st/emit! (dwt/update-editor-state shape state)))
           "handled"))

        on-click
        (mf/use-callback
         (fn [event]
           (when (dom/class? (dom/get-target event) "DraftEditor-root")
             (st/emit! (dwt/cursor-to-end shape)))
           (st/emit! (dwt/focus-editor))))

        handle-pasted-text
        (fn [text _ _]
          (let [style (ted/get-editor-current-inline-styles state)
                state (-> (ted/insert-text state text style)
                          (handle-change))]
            (st/emit! (dwt/update-editor-state shape state)))

          "handled")]

    (mf/use-layout-effect on-mount)

    [:div.text-editor
     {:ref self-ref
      :style {:cursor (cur/text (:rotation shape))
              :width (:width shape)
              :height (:height shape)}
      :on-click on-click
      :class (dom/classnames
              :align-top    (= (:vertical-align content "top") "top")
              :align-center (= (:vertical-align content) "center")
              :align-bottom (= (:vertical-align content) "bottom"))}
     [:> draft/Editor
      {:on-change on-change
       :on-blur on-blur
       :on-focus on-focus
       :handle-return handle-return
       :strip-pasted-styles true
       :handle-pasted-text handle-pasted-text
       :custom-style-fn styles-fn
       :block-renderer-fn #(render-block % shape)
       :ref on-editor
       :editor-state state}]]))

(defn translate-point-from-viewport
  "Translate a point in the viewport into client coordinates"
  [pt viewport zoom]
  (let [vbox     (.. ^js viewport -viewBox -baseVal)
        box      (gpt/point (.-x vbox) (.-y vbox))
        zoom     (gpt/point zoom)]
    (-> (gpt/subtract pt box)
        (gpt/multiply zoom))))

(mf/defc text-editor-viewport
  {::mf/wrap-props false}
  [props]
  (let [shape        (obj/get props "shape")
        viewport-ref (obj/get props "viewport-ref")
        zoom         (obj/get props "zoom")

        position
        (-> (gpt/point (-> shape :selrect :x)
                       (-> shape :selrect :y))
            (translate-point-from-viewport (mf/ref-val viewport-ref) zoom))]

    [:div {:style {:position "absolute"
                   :left (str (:x position) "px")
                   :top  (str (:y position) "px")
                   :pointer-events "all"
                   :transform (str (gsh/transform-matrix shape nil (gpt/point 0 0)))
                   :transform-origin "center center"}}

     [:div  {:style {:transform (str "scale(" zoom ")")
                     :transform-origin "top left"}}
      [:& text-shape-edit-html {:shape shape :key (str (:id shape))}]]]))
