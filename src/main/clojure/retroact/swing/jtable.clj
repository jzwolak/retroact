(ns retroact.swing.jtable
  (:import (javax.swing JTable)
           (javax.swing.table AbstractTableModel)
           (retroact.swing.compiled.jtable RTableModel)))

; Pass arguments to create-table-model that define how to access the data from the app-value. Also pass the app-value.
; Then set the table model on the JTable. Just create a new table model when the way to access the data changes. But
; if app-value changes, then we should be able to set that somewhere without recreating the table model... maybe using
; ... I don't know.

#_(defn create-table-model []
  (let [model (atom {:data []})]
    (proxy [AbstractTableModel]
           (.setData))))

(defn safe-table-model-set [table fn attribute]
  (when (instance? JTable table)
    (let [model (.getModel table)]
      (fn model attribute))))

(defn create-jtable []
  (JTable. (RTableModel.)))
