(ns retroact.swing.jcombobox
  (:import (javax.swing DefaultComboBoxModel JComboBox)))

(defn create-jcombobox [ctx]
  (let [jcombobox (JComboBox. (DefaultComboBoxModel.))]
    ; If I decide to use this, see jlist namespace for implementation and perhaps just call the jlist fn.
    #_(.setCellRenderer jcombobox (create-cell-renderer))
    jcombobox))
