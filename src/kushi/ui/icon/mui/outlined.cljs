(ns kushi.ui.icon.mui.outlined
  (:require
   [kushi.core :refer (sx inject-stylesheet merge-with-style)]
   [kushi.ui.core   :refer (defcom)]))

(inject-stylesheet {:rel "preconnet"
                    :href "https://fonts.gstatic.com"
                    :cross-origin "anonymous"})
(inject-stylesheet {:rel "preconnet"
                    :href "https://fonts.googleapis.com"})
(inject-stylesheet {:rel "stylesheet"
                    :href "https://fonts.googleapis.com/css2?family=Material+Icons+Outlined"})
#_(def mui-icon-span
  [:span:!children
   (sx
    'kushi-mui-icon
    :.transition
    :.material-icons-outlined
    {:data-kushi-ui :icon.mui.outlined
     :style {:&.material-icons:fs "var(--mui-icon-relative-font-size)"}})])

(defcom mui-icon-outlined
  [:div
   (merge-with-style
    (sx
     'kushi-icon
     :.relative
     :.transition
     :.flex-row-c
     :ta--center
     :d--ib
     :ai--c
     {:data-kushi-ui :icon})
    &attrs)
   [:span
    (sx
     'kushi-mui-icon
     :.transition
     :.material-icons-outlined
     {:data-kushi-ui :icon.mui.outlined
      :style         {:&.material-icons:fs "var(--mui-icon-relative-font-size)"}})
    &children]])
