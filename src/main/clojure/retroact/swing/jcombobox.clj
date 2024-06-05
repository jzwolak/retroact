(ns retroact.swing.jcombobox
  (:require [retroact.swing.jlist :as jlist])
  (:import (javax.swing DefaultComboBoxModel JComboBox)))

(defn create-jcombobox [{:keys [view] :as ctx}]
  (if-let [render-fn (:render view)]
    (let [jcombobox (JComboBox. (DefaultComboBoxModel.))]
      (.setRenderer jcombobox (jlist/create-retroact-cell-renderer ctx render-fn))
      jcombobox)
    (let [jcombobox (JComboBox. (DefaultComboBoxModel.))]
      jcombobox)))
