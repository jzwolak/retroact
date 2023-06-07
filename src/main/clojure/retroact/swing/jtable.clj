(ns retroact.swing.jtable
  (:require [clojure.tools.logging :as log])
  (:import (javax.swing JScrollPane JTable)
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

(defn safe-table-model-set [table f attribute]
  (cond
    (instance? JTable table)
    (let [model (.getModel table)]
      (f model attribute))
    (instance? JScrollPane table) (safe-table-model-set (-> table (.getViewport) (.getView)) f attribute)
    :else (log/error "skipping fn on table because component does not appear to be a table: " table)))

(defn create-jtable [ui]
  (log/info "creating jtable")
  (if (:headers ui)
    (JScrollPane. (JTable. (RTableModel.)))
    (JTable. (RTableModel.))))
