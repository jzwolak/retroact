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

(defn safe-table-set [table f attribute]
  (cond
    (instance? JTable table) (f table attribute)
    (instance? JScrollPane table) (safe-table-set (-> table (.getViewport) (.getView)) f attribute)
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

(defn set-table-auto-resize-mode
  "Set JTable auto-resize mode. Accepts JTable/AUTO_RESIZE_ constants."
  [c ctx mode]
  (safe-table-set c (fn [^JTable t m]
                      (when-let [val m]
                        (.setAutoResizeMode t val)))
                  mode))

; TODO: this doesn't work because it is first run before the table column is created
(defn- set-table-columns- [table columns]
  (let [column-model (.getColumnModel table)
        cm-count (.getColumnCount column-model)
        model-count (.getColumnCount (.getModel table))]
    ; Ensure columns are created from the model when needed (e.g., empty data but headers present).
    (when (< cm-count model-count)
      (.createDefaultColumnsFromModel table))
    (doseq [[n column] columns]
      (let [column-model (.getColumnModel table)]
        (when (and (>= n 0) (< n (.getColumnCount column-model)))
          (let [table-column (.getColumn column-model n)]
            (when-let [preferred-width (cond
                                         (map? column) (get column :preferred-width)
                                         (number? column) column
                                         :else nil)]
              (.setPreferredWidth table-column preferred-width))
            (when-let [max-width (get column :max-width)]
              (.setMaxWidth table-column max-width))
            (when-let [min-width (get column :min-width)]
              (.setMinWidth table-column min-width))))))))

(defn set-table-columns
  [c ctx columns]
  (safe-table-set c set-table-columns- columns))

(defn create-jtable [{:keys [view]}]
  (let [table-model (RTableModel.)
        table (JTable. ^TableModel table-model)]
    (.setTableComponent table-model table)
    ; TODO: this is a strange decision. Shouldn't it always be in a JScrollPane? Or never? Or perhaps test to see if it is already in one.
    (if (:headers view)
      (JScrollPane. table)
      table)))
