(ns kushi.ui.icon.core
  (:require
   [kushi.core :refer (sx merge-attrs)]
   [kushi.ui.core :refer (defcom)]))

(defcom icon
  [:div
   (merge-attrs
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
   &children])
