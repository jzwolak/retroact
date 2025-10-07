 (ns retroact.jtable-test
   (:require [clojure.test :refer :all]
             [retroact.swing.jtable :as rjt])
   (:import (javax.swing JTable SwingUtilities SortOrder)
            (javax.swing.table TableModel)
            (retroact.swing.compiled.jtable RTableModel)))

 (deftest column-count-uses-column-names-when-no-data
   (let [model (RTableModel.)]
     ;; Ensure this runs on EDT as model methods tend to expect it.
     (SwingUtilities/invokeAndWait
       (fn []
         (.setColumnNames model ["A" "B" "C"]) ; IPersistentVector
         (is (= 3 (.getColumnCount model)))))))

 (deftest can-set-preferred-column-widths-without-data
   (let [model (RTableModel.)
         table-holder (atom nil)]
     (SwingUtilities/invokeAndWait
       (fn []
         ;; Set headers first (no data yet) — columns should be materialized from header count
         (.setColumnNames model ["One" "Two" "Three"]) ; 3 columns
         (let [^JTable table (JTable. ^TableModel model)]
           (reset! table-holder table)
           ;; Apply preferred widths via Retroact's API
           (rjt/set-table-columns table nil [[0 {:preferred-width 120}]
                                             [2 {:preferred-width 60}]])
           (let [cm (.getColumnModel table)]
             (is (= 120 (.getPreferredWidth (.getColumn cm 0))))
             (is (= 60 (.getPreferredWidth (.getColumn cm 2))))))))))

 (deftest table-sort-ascending-by-column
   (let [model (RTableModel.)
         table-holder (atom nil)]
     (SwingUtilities/invokeAndWait
       (fn []
         ;; Model with one column, using identity row-fn so rows are vectors
         (.setColumnNames model ["Num"]) ; 1 column
         (.setRowFn model identity)
         (.setData model [[2] [1] [3]])
         (let [^JTable table (JTable. ^TableModel model)]
           (reset! table-holder table)
           ;; Sort ascending by column 0
           (rjt/set-table-sort table nil [0 SortOrder/ASCENDING])
           (is (= 1 (.getValueAt table 0 0)))
           (is (= 2 (.getValueAt table 1 0)))
           (is (= 3 (.getValueAt table 2 0))))))))
