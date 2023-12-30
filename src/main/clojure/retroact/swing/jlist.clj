(ns retroact.swing.jlist
  (:require [clojure.tools.logging :as log])
  (:import (javax.swing DefaultListModel JList ListCellRenderer)))



(defn- create-cell-renderer []
  (reify ListCellRenderer
    (getListCellRendererComponent [this list value index isSelected cellHasFocus]
      (let [model (.getModel list)
            child (.getElementAt model index)]
        (if isSelected
          (do
            (.setBackground child (.getSelectionBackground list))
            (.setForeground child (.getSelectionForeground list)))
          (do
            (.setBackground child (.getBackground list))
            (.setForeground child (.getForeground list))))
        (.setEnabled child (.isEnabled list))
        child))))

(defn create-jlist [ui]
  (let [jlist (JList. (DefaultListModel.))]
    (.setCellRenderer jlist (create-cell-renderer))
    jlist))
