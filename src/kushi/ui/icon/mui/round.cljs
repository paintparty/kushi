(ns kushi.ui.icon.mui.round
  (:require
   [kushi.core  :refer (sx inject-stylesheet merge-with-style)]
   [kushi.ui.core   :refer (defcom)]))

(inject-stylesheet {:rel "preconnet"
                    :href "https://fonts.gstatic.com"
                    :cross-origin "anonymous"})
(inject-stylesheet {:rel "preconnet"
                    :href "https://fonts.googleapis.com"})
(inject-stylesheet {:rel "stylesheet"
                    :href "https://fonts.googleapis.com/css2?family=Material+Icons+Round"})

(defcom mui-icon-round
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
     {:data-kushi-ui :icon.mui.round
      :style         {:&.material-icons:fs "var(--mui-icon-relative-font-size)"}})
    &children]])
