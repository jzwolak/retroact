(ns retroact.swing.compiled.jtable
  (:require [clojure.tools.logging :as log])
  (:import (clojure.lang IPersistentVector IFn)))


; TODO: gen-class is causing all kinds of problems downstream unrelated to gen-class. Clojurephant for instance will not
; accept a single AOT compiled namespace and still include the other namespaces in the jar, the classpath for composite
; builds, and the classpath for other dependent tasks.

; Take an
; - app ref
; - fn to get data (or path to data) as vector of rows
; - fn to get colomns from a row
; - or maybe just a fn to get matrix of data
; Retroact knows when the data has changed and can tell the table model to update. If the app ref has changed or any of
; the data fns, then Retroact creates a new table model and sets the new table model on the JTable.
(gen-class
  :name "retroact.swing.compiled.jtable.RTableModel"
  :extends javax.swing.table.AbstractTableModel
  :state "state"
  :init "init-state"
  :prefix "rtable-model-"
  :methods [[setRowFn [clojure.lang.IFn] void]
            [setData [clojure.lang.IPersistentVector] void]
            [setRowEditableFn [clojure.lang.IFn] void]
            [setSetValueAtFn [clojure.lang.IFn] void]])

(defn rtable-model-init-state []
  (log/info "init table state")
  (let [state (atom {:data   []
                     :row-fn identity})]
    (log/info "returning from init table state")
    [[] state]))

(defn rtable-model-getRowCount [this] (let [state @(.state this)] (count (:data state))))

(defn rtable-model-getColumnCount [this]
  (log/info "get column count")
  (let [state @(.state this)
        data (:data state)
        first-row (first data)
        row-fn (:row-fn state)]
    (if first-row
      (count (row-fn first-row))
      0)))

(defn rtable-model-getColumnName [this col]
  (log/info "get column name")
  (let [state @(.state this)
        column-names (:column-names state)]
    (if column-names
      (nth column-names col)
      "")))

(defn rtable-model-getColumnClass [this col]
  (let [state @(.state this)
        row-fn (:row-fn state)
        first-row (get-in state [:data 0])
        cell (if first-row (nth (row-fn first-row) col) "")]
    (.getClass cell)))

(defn rtable-model-isCellEditable [this row col]
  (let [state @(.state this)
        ; make sure row-editable-fn and data have meaningful defaults so that the following code doesn't need
        ; branching.
        row-editable-fn (or (:row-editable-fn state) (fn [_] (repeat false)))
        data (or (:data state) [])]
    (-> data
        (nth row (last data)) ; get row and default to (last data) if row is out of bounds
        (row-editable-fn)                                   ; get editability of columns in row
        (nth col false))))                                  ; get editability of column, default to false

(defn rtable-model-getValueAt [this row col]
  (log/info "get value at" row "," col)
  (let [state @(.state this)
        row-fn (:row-fn state)
        data (:data state)]
    (-> data
        (nth row)
        (row-fn)
        (nth col))))

(defn rtable-model-setValueAt [this row col value]
  ; TODO: implement and call setValueAtFn if present
  ; perhaps if setValueAtFn is present and isCellEditableFn is not, then all cells should be editable.
  )

(defn rtable-model-setData [this data]
  (let [state-atom (.state this)]
    (log/info "setting table data to" data)
    (swap! state-atom assoc :data data)
    (.fireTableStructureChanged this)))

(defn rtable-model-setRowFn [this row-fn]
  (let [state-atom (.state this)]
    (log/info "set row fn")
    (swap! state-atom assoc :row-fn row-fn)
    (.fireTableStructureChanged this)))

(defn rtable-model-setRowEditableFn [this row-editable-fn]
  (let [state-atom (.state this)]
    (swap! state-atom assoc :row-editable-fn row-editable-fn)))

(defn rtable-model-setSetValueAtFn [this row-editable-fn]
  (let [state-atom (.state this)]
    (swap! state-atom assoc :row-editable-fn row-editable-fn)))
