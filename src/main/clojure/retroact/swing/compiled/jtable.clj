(ns retroact.swing.compiled.jtable
  (:require [clojure.tools.logging :as log]))


; TODO: I could use a `:gen-class` in the `ns` form above. That may simplify this file.


; TODO: gen-class is causing all kinds of problems downstream unrelated to gen-class. Clojurephant for instance will not
; accept a single AOT compiled namespace and still include the other namespaces in the jar, the classpath for composite
; builds, and the classpath for other dependent tasks.

; Take an
; - app ref
; - fn to get data (or path to data) as vector of rows
; - fn to get colomns from a row (row-fn)
; - or maybe just a fn to get matrix of data (this idea was discarded, though, the default row-fn is the identity fn)
; Retroact knows when the data has changed and can tell the table model to update. If the app ref has changed or any of
; the data fns, then Retroact creates a new table model and sets the new table model on the JTable.
(gen-class
  :name "retroact.swing.compiled.jtable.RTableModel"
  :extends javax.swing.table.AbstractTableModel
  :state "state"
  :init "init-state"
  :prefix "rtable-model-"
  :methods [[getItemAt [java.lang.Integer] Object]
            [setColumnNames [clojure.lang.IPersistentVector] void]
            [setData [clojure.lang.IPersistentVector] void]
            [setRowFn [clojure.lang.IFn] void]
            [setRowEditableFn [clojure.lang.IFn] void]
            [setSetValueAtFn [clojure.lang.IFn] void]])

(defn rtable-model-init-state []
  (let [state (atom {:data   []
                     :row-fn identity})]
    [[] state]))

(defn rtable-model-getRowCount [this] (let [state @(.state this)] (count (:data state))))

(defn rtable-model-getColumnCount [this]
  (let [state @(.state this)
        data (:data state)
        first-row (first data)
        row-fn (:row-fn state)]
    (if first-row
      (count (row-fn first-row))
      0)))

(defn rtable-model-getColumnName [this col]
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

(defn- get-item-at [state row]
  (let [data (:data state)]
    (nth data row)))

(defn- get-value-at [state row col]
  (let [row-fn (:row-fn state)
        item (get-item-at state row)]
    (-> item
        (row-fn)
        (nth col))))

(defn rtable-model-getItemAt [this row]
  (log/info "getting item at row " row)
  (get-item-at @(.state this) row))

(defn rtable-model-getValueAt [this row col]
  (get-value-at @(.state this) row col))

(defn rtable-model-setValueAt [this value row col]
  ; TODO: implement and call setValueAtFn if present
  ; perhaps if setValueAtFn is present and isCellEditableFn is not, then all cells should be editable.
  (let [state @(.state this)
        set-value-at-fn (get state :set-value-at-fn (fn [old-item new-value row col]))
        old-item (get-item-at state row)]
    (set-value-at-fn old-item value row col)))

(defn rtable-model-setColumnNames [this column-names]
  (let [state-atom (.state this)]
    (swap! state-atom assoc :column-names column-names)
    (.fireTableStructureChanged this)))

(defn rtable-model-setData [this data]
  (let [state-atom (.state this)]
    (swap! state-atom assoc :data data)
    (.fireTableStructureChanged this)))

(defn rtable-model-setRowFn [this row-fn]
  (let [state-atom (.state this)]
    (swap! state-atom assoc :row-fn row-fn)
    (.fireTableStructureChanged this)))

(defn rtable-model-setRowEditableFn [this row-editable-fn]
  (let [state-atom (.state this)]
    (if row-editable-fn
      (swap! state-atom assoc :row-editable-fn row-editable-fn)
      (swap! state-atom dissoc :row-editable-fn))))

(defn rtable-model-setSetValueAtFn [this set-value-at-fn]
  (let [state-atom (.state this)]
    (if set-value-at-fn
      (swap! state-atom assoc :set-value-at-fn set-value-at-fn)
      (swap! state-atom dissoc :set-value-at-fn))))
