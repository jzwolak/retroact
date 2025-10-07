 (ns retroact.jtable-test
   (:require [clojure.test :refer :all]
             [retroact.swing.jtable :as rjt])
   (:import (javax.swing JTable SwingUtilities)
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
