(ns kushi.ui.collapse.core
  (:require-macros [kushi.utils :refer (keyed)])
  (:require
   [kushi.core :refer (sx defclass merge-with-style) :refer-macros (sx)]
   [clojure.string :as string]
   [kushi.ui.collapse.header :refer (collapse-header-contents)]
   [kushi.ui.collapse.footer :refer (collapse-footer-contents)]
   [kushi.gui :refer (gui defcom)]
   ))

(def ? js/console.log)
;; Accordian with multiple expandable
;; Dual animation
;; positioning of icon
;; make :. work in class vectors

(defcom collapse-body
  [:section
   (sx
    :overflow--hidden)
   [:div:! (sx
            :bt--1px:solid:transparent
            :bb--1px:solid:transparent)]])

(defn toggle-class-on-ancestor [node root-class class]
  (let [root (.closest node (str "." (name root-class)))]
    (when root (.toggle (.-classList root) (name class)))))

(defn toggle-boolean-attribute [node attr]
  (let [aria-expanded? (.getAttribute node (name attr))
        newv (if (= aria-expanded? "false") true false)]
    (.setAttribute node (name attr) newv)))

(defn outer-height [el]
(let [styles (js/window.getComputedStyle el)
      margin-top (js/parseFloat (.-marginTop styles))
      margin-bottom (js/parseFloat (.-marginBottom styles))
      ret  (+ margin-top margin-bottom (js/Math.ceil (.-offsetHeight el)))]
  #_(? {:styles styles
      :margin-top margin-top
      :margin-bottom margin-bottom
      :ret ret})
  ret))

(defclass collapse-header
  :.pointer
  :ai--center
  :p--10px:0px
  :transition--all:200ms:linear)

(defcom collapse-header
  (let [on-click #(let [node        (.closest (-> % .-target) "[aria-expanded][role='button']")
                        exp-parent  (-> node .-nextSibling)
                        exp-inner   (-> node .-nextSibling .-firstChild)
                        exp-inner-h (outer-height exp-inner)
                        expanded?   (= "true" (.getAttribute node (name :aria-expanded)))
                        height      (str exp-inner-h "px")
                        ->height    (if expanded? "0px" height)
                        no-height?  (and expanded? (string/blank? exp-parent.style.height))]

                    (when no-height? (set! exp-parent.style.height height))
                    (js/window.requestAnimationFrame (fn []
                                                       (set! exp-parent.style.height ->height)
                                                       (toggle-boolean-attribute node :aria-expanded))))]
    [:div
     (sx
      {:class         [:.flex-row-fs :.collapse-header]
       :style         {
                       :+section:transition-property                     :height
                       :+section:transition-timing-function              "cubic-bezier(0.23, 1, 0.32, 1)"
                       :+section:transition-duration                     :500ms
                       "&[aria-expanded='false']:+section:height"        :0px

                       "&[aria-expanded='false']:+section:>*:transition" :opacity:200ms:linear:10ms
                       "&[aria-expanded='true']:+section:>*:transition"  :opacity:200ms:linear:200ms

                       "&[aria-expanded='false']:+section:>*:opacity"    0
                       "&[aria-expanded='true']:+section:>*:opacity"     1}
       :role          :button
       :aria-expanded false
       :on-click      on-click})]))

(defn get-attr [m k] (some-> m :parts k first))
(defn get-children [m k] (some-> m :parts k rest))

(defn collapse
  "A section of content which can be collapsed and expanded"
  [{:keys [parts label-text label-text-expanded icon-type on-click] :as opts} & children]
  (let [attrs (partial get-attr opts)
        childs (partial get-children opts)]
    [:section
     (merge-with-style
      (sx :.flex-col-fs :w--100%)
      (attrs :wrapper))
     [collapse-header
      (merge-with-style
       (attrs :header)
       (sx {:on-click on-click})
       )
      #_[collapse-footer-contents {:label-text label-text :label-text-expanded label-text-expanded}]
      [collapse-header-contents {:label-text label-text :label-text-expanded label-text-expanded :icon-type icon-type} #_(keyed label-text label-text-expanded icon-type)]]
     [collapse-body (or (attrs :body) {}) children]]))