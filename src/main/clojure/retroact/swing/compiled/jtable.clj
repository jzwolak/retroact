(ns retroact.swing.compiled.jtable
  (:require [clojure.tools.logging :as log])
  (:import (javax.swing ListSelectionModel SwingUtilities)))


;; --------------------------------------------------------------------------------
;; RTableModel (compiled) — documentation and design notes
;; --------------------------------------------------------------------------------
;; GPT-5 generated documentation... read at your own risk. Much of it seems correct.
;; -- Jason Zwolak (2025-09-09)
;; Purpose
;; - RTableModel extends javax.swing.table.AbstractTableModel and is used by Retroact
;;   to back JTable components. It stores all model-related values in an atom `state`
;;   and exposes a small set of mutator methods that Retroact calls when attributes
;;   change.
;;
;; State (keys and meanings)
;; - :data                Vector of application items (domain rows). Each item is a
;;                        value from the app domain (often a map). This model treats
;;                        :data as immutable snapshots; when it changes, we fire a
;;                        full structure change.
;; - :row-fn              Function of one item -> vector of cell values for that row.
;;                        The length of this vector defines the column count. Defaults
;;                        to identity, which implies items should already be vectors
;;                        of cell values if you rely on the default.
;; - :column-names        Vector of strings shown by JTable as column headers.
;; - :row-editable-fn     Function of one item -> vector<boolean> per column indicating
;;                        whether each cell in that row is editable. If absent, cells
;;                        are considered non-editable. When present, `isCellEditable`
;;                        consults it.
;; - :set-value-at-fn     Function called when the user edits a cell:
;;                        (fn [old-item new-value row col] ...). The model does NOT
;;                        modify :data itself; it delegates to this function so the
;;                        application can update its own state appropriately. When
;;                        application state is updated this table will (presumably)
;;                        receive the updates through the :table-data attribute. The
;;                        application is supposed to use that attribute to set the
;;                        table data.
;;
;; Notably missing vs. JTree analogs (as of this file):
;; - There is no selection management in the model (no :table-selection,
;;   :table-selection-fn, or setTableComponent). Selection for JTable appears to be
;;   handled elsewhere in Retroact (see e.g. :row-selection-interval /
;;   :column-selection-interval attr appliers in retroact.swing). If a model-driven
;;   selection API (analogous to JTree's) is desired, it is not implemented here.
;; - There is no custom render pipeline or `:table-render-fn` wiring. JTable normally
;;   renders via TableCellRenderer. This file does not attach or proxy a renderer.
;;   Unknown: whether a future compiled renderer is planned (like RTreeCellRenderer).
;;
;; Threading / EDT notes
;; - This file does not enforce EDT usage or emit warnings if called off the EDT,
;;   unlike the JTree compiled model which logs errors when not on the EDT.
;;   Unknown: callers may still ensure EDT usage; this file does not verify it.
;;
;; Method summary / behavior notes
;; - init-state: initializes state with {:data [] :row-fn identity}.
;; - getRowCount: returns (count :data).
;; - getColumnCount: inspects the first row, applies :row-fn, and uses the length of
;;   the resulting vector. If there is no row, returns 0.
;; - getColumnName: returns (nth :column-names col) or "".
;; - getColumnClass: applies :row-fn to the first row, fetches the `col` value, and
;;   returns its Java class. If there is no data, uses "" as a placeholder value and
;;   returns String's class.
;; - isCellEditable: obtains a vector of booleans from :row-editable-fn item-wise; if
;;   either the function or vector is missing, defaults to false. When the row index
;;   is out of bounds, the last row is used as a fallback (this is a convenience; it
;;   may hide index errors).
;; - getItemAt: returns the raw app item (not the row-fn result) at `row`.
;; - getValueAt: returns the cell value via (-> item row-fn (nth col)).
;; - setValueAt: delegates to :set-value-at-fn with (old-item value row col). The model
;;   does not mutate :data here. Unknown: whether consumers call `fireTableCellUpdated`
;;   or re-render externally after updating application state.
;; - setColumnNames: sets :column-names and fires a structure change.
;; - setData: replaces :data and fires a structure change (no diffing performed).
;; - setRowFn: sets :row-fn and fires a structure change.
;; - setRowEditableFn: sets or removes :row-editable-fn. Does not fire structure change
;;   (JTable typically queries editability per cell; if needed, callers may request a
;;   repaint). This is a design choice.
;; - setSetValueAtFn: sets or removes :set-value-at-fn. No events fired.
;;
;; Unknowns / assumptions called out explicitly
;; - Selection: Model-driven selection analogous to JTree is not part of this file.
;;   Any selection behavior appears to be handled by higher-level attr appliers on the
;;   JTable component, not the model.
;; - Rendering: No `setRenderFn` or renderer gen-class exists here; rendering is left
;;   to JTable's default or external configuration.
;; - Post-edit events: `setValueAt` does not fire finer-grained model events (like
;;   fireTableCellUpdated). If granular updates are desired, additional event calls may
;;   be needed after application state changes.
;; --------------------------------------------------------------------------------


; TODO: I could use a `:gen-class` in the `ns` form above. That may simplify this file.


; TODO: gen-class is causing all kinds of problems downstream unrelated to gen-class. Clojurephant for instance will not
; accept a single AOT compiled namespace and still include the other namespaces in the jar, the classpath for composite
; builds, and the classpath for other dependent tasks.

; Take an
; - app ref
; - fn to get data (or path to data) as vector of rows
; - fn to get columns from a row (row-fn)
; - or maybe just a fn to get matrix of data (this idea was discarded, though, the default row-fn is the identity fn)
; Retroact knows when the data has changed and can tell the table model to update. If the app ref has changed or any of
; the data fns, then Retroact creates a new table model and sets the new table model on the JTable.
(gen-class
  :name "retroact.swing.compiled.jtable.RTableModel"
  :extends javax.swing.table.AbstractTableModel
  :state "state"
  :init "init-state"
  :post-init "post-init"
  :prefix "rtable-model-"
  :methods [[getItemAt [java.lang.Integer] Object]
            [setColumnNames [clojure.lang.IPersistentVector] void]
            [setData [clojure.lang.IPersistentVector] void]
            [setGetItemAtFn [clojure.lang.IFn] void]
            [setRowFn [clojure.lang.IFn] void]
            [setRowEditableFn [clojure.lang.IFn] void]
            [setSelection [clojure.lang.IPersistentVector] void]
            [setSelectionFn [clojure.lang.IFn] void]
            [setSetValueAtFn [clojure.lang.IFn] void]
            [setTableComponent [javax.swing.JTable] void]])

(defn- table-model-watch
  [this _key _ref old-value new-value]
  (let [old-data (:data old-value)
        new-data (:data new-value)
        table-selection-old (get old-value :table-selection)
        table-selection-new (get new-value :table-selection)]
    (when (not (SwingUtilities/isEventDispatchThread))
      (log/error (RuntimeException. "RTableModel method called off EDT.")
                 (str "RTableModel method called off EDT. RTableModel methods should be called on EDT. "
                      "Continuing, but behavior may not be correct.")))
    (when (not= table-selection-old table-selection-new)
      (let [selectionModel ^ListSelectionModel (.getSelectionModel (:table-component new-value))]
        #_(.clearSelection selectionModel)
        (doseq [row table-selection-new]
          (when (not (.isSelectedIndex selectionModel row))
            (.addSelectionInterval selectionModel row row)))))
    ; The following may not be a good idea. Or maybe it needs to happen only when table data has changed
    ; not always.
    #_(when (not= old-data new-data)
      (.fireTableStructureChanged this))))

(defn rtable-model-init-state []
  (let [state (atom {:data   []
                     :row-fn identity})]
    [[] state]))

(defn rtable-model-post-init [this & _]
  (add-watch (.state this) :table-model-self-watch (partial table-model-watch this)))

(defn rtable-model-getRowCount [this] (let [state @(.state this)] (count (:data state))))

(defn rtable-model-getColumnCount [this]
  (let [state @(.state this)
        data (:data state)
        first-row (first data)
        row-fn (:row-fn state)
        column-names (:column-names state)]
    (cond
      ; Prefer explicit column headers when provided.
      (some? column-names) (count column-names)
      ; Otherwise infer from the first row via row-fn.
      first-row (count (row-fn first-row))
      ; No data and no headers => zero columns.
      :else 0)))

(defn rtable-model-getColumnName [this col]
  (let [state @(.state this)
        column-names (:column-names state)]
    (if column-names
      (nth column-names col)
      "")))

(defn- get-item-at [state row]
  (if-let [get-item-at-fn (:get-item-at-fn state)]
    (get-item-at-fn (:data state) row)
    (let [data (:data state)]
      (get data row))))

(defn rtable-model-getColumnClass [this col]
  (let [state @(.state this)
        row-fn (:row-fn state)
        first-item (get-item-at state 0)
        cell (if first-item (get (row-fn first-item) col) "")]
    (if (nil? cell)
      Object
      (.getClass cell))))

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

(defn- get-value-at [state row col]
  (let [row-fn (:row-fn state)
        item (get-item-at state row)]
    (-> item
        (row-fn)
        (get col))))

(defn rtable-model-getItemAt
  "Return the application state associated with the given row. Defaults to nth of :data.
  Can be overriden by providing a :get-item-at-fn function."
  [this row]
  (log/info "getting item at row " row)
  (get-item-at @(.state this) row))

(defn rtable-model-setGetItemAtFn [this get-item-at-fn]
  (let [state-atom (.state this)]
    (swap! state-atom assoc :get-item-at-fn get-item-at-fn)))

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

(defn- update-table-selection [{:keys [data table table-selection-fn] :as state}]
  (if table-selection-fn
    (assoc state :table-selection (table-selection-fn data))
    (dissoc state :table-selection)))

(defn- update-table [state]
  (-> state
    #_(let [data (:data state)
          ; TODO: not correct, use the correct fn whatever it is, that get's table rows and columns.
          table-model-fn (:table-model-fn state)]
      ; TODO: return full modified state from let. Following may be wrong.
      #_(assoc state :table-data (table-model-fn data)))
    (update-table-selection)))

(defn- update-data [state data]
  (if (= (:data state) data)
    state
    (update-table (assoc state :data data)))
  )

(defn rtable-model-setData [this data]
  (swap! (.state this) update-data data)
  (.fireTableStructureChanged this))

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

(defn rtable-model-setTableComponent [this table]
  (let [state-atom (.state this)]
    (swap! state-atom assoc :table-component table)))

(defn rtable-model-setSelection [this table-selection]
  (let [state-atom (.state this)]
    (swap! state-atom assoc :table-selection table-selection)))

(defn rtable-model-setSelectionFn [this selection-fn]
  (let [state-atom (.state this)]
    (swap! state-atom assoc :table-selection-fn selection-fn)))
