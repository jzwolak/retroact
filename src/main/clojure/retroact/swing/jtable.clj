(ns retroact.swing.jtable
  (:require [clojure.tools.logging :as log])
  (:import (javax.swing JScrollPane JTable)
           (javax.swing.table AbstractTableModel TableModel)
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

(defn set-table-selection-fn
  "Set the selection function for a JTable. This function will be called when table selection changes.
  Analogous to :tree-selection-fn for JTree."
  [c ctx table-selection-fn]
  (safe-table-model-set c (memfn setSelectionFn table-selection-fn) table-selection-fn))

(defn set-table-render-fn
  "Set the render function for a JTable. This function customizes how cells are rendered.
  Analogous to :tree-render-fn for JTree."
  [c ctx table-render-fn]
  (safe-table-model-set c (memfn setRenderFn table-render-fn) table-render-fn))

(defn set-table-set-value-at-fn
  "Set the function that handles cell value changes when cells are edited.
  This provides a way to handle cell editing events."
  [c ctx set-value-at-fn]
  (safe-table-model-set c (memfn setSetValueAtFn set-value-at-fn) set-value-at-fn))

(defn set-table-get-item-at-fn
  [c ctx table-get-item-at-fn]
  (safe-table-model-set c (memfn setGetItemAtFn table-get-item-at-fn) table-get-item-at-fn))

(defn create-jtable [{:keys [view]}]
  (let [table-model (RTableModel.)
        table (JTable. ^TableModel table-model)]
    (.setTableComponent table-model table)
    (if (:headers view)
      (JScrollPane. table)
      table)))

