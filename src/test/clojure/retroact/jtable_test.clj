 (ns retroact.jtable-test
   (:require [clojure.string :as str]
             [clojure.test :refer :all]
             [retroact.core :as r]
             [retroact.swing.jtable :as rjt]
             [retroact.swing.util :as su])
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

 ;; --- :selection ---------------------------------------------------------------
 ;; The tests below render a table through Retroact, as an app does. The app
 ;; state holds the rows and the selection. The view is a function of that state.

 (defn- upper-case-row [row] (mapv str/upper-case row))

 (defn- selection-table-view [app-val]
   {:class        :table
    :id           ::selection-table
    :table-data   (:rows app-val)
    :column-names (:column-names app-val)
    :row-fn       (:row-fn app-val)
    :selection    (:selection app-val)})

 (defn- selection-table-render [app-ref app-val]
   {:class    :panel
    :contents [(selection-table-view app-val)]})

 (defn- on-edt
   "Run f on the EDT and return the result of f."
   [f]
   (let [result (promise)]
     (SwingUtilities/invokeAndWait (fn deliver-result [] (deliver result (f))))
     @result))

 (defn- render-table!
   "Merge changes into the app state and wait until Retroact rendered the table
    for the new state. Return the JTable. Retroact stores the view of a
    component on that component after Retroact applied all attributes of the
    view. Thus the stored view shows that the render is complete."
   [app-ref changes]
   (let [expected-view (selection-table-view (swap! app-ref merge changes))
         deadline (+ (System/currentTimeMillis) 5000)]
     (loop []
       (let [table (r/get-comp ::selection-table)]
         (cond
           (and table (= expected-view (on-edt (fn stored-view [] (su/get-view table))))) table
           (< deadline (System/currentTimeMillis))
           (throw (ex-info "Retroact did not render the table" {:view expected-view}))
           :else (do (Thread/sleep 10) (recur)))))))

 (defn- selected-rows [^JTable table]
   (on-edt (fn get-selected-rows [] (vec (.getSelectedRows table)))))

 (defn- create-selection-table!
   "Create a Retroact app that shows one table and return the app-ref."
   [app-val]
   (let [app-ref (r/init-app-ref (atom (merge {:column-names ["Letter"] :row-fn identity} app-val)))]
     (r/create-comp app-ref {:render selection-table-render})
     app-ref))

 (deftest selection-survives-a-change-of-the-table-data
   (let [app-ref (create-selection-table! {:rows [["a"] ["b"] ["c"]] :selection [1]})
         table (render-table! app-ref {})]
     (is (= [1] (selected-rows table)) "Precondition: the row of :selection is selected.")
     (render-table! app-ref {:rows [["a"] ["b changed"] ["c"]]})
     (is (= [1] (selected-rows table))
         "The view continues to give row 1 as the selection. Row 1 must stay selected after the table data changes.")))

 (deftest selection-follows-the-view-when-data-and-selection-change-together
   (let [app-ref (create-selection-table! {:rows [["a"] ["b"] ["c"]] :selection [2]})
         table (render-table! app-ref {})]
     (is (= [2] (selected-rows table)) "Precondition: the row of :selection is selected.")
     ;; The app removes row "a". The selected item "c" moves from row 2 to row 1.
     (render-table! app-ref {:rows [["b"] ["c"]] :selection [1]})
     (is (= [1] (selected-rows table))
         "Only the row of the new :selection must be selected in the new table data.")))

 (deftest selection-survives-a-change-of-the-column-names
   (let [app-ref (create-selection-table! {:rows [["a"] ["b"] ["c"]] :selection [1]})
         table (render-table! app-ref {})]
     (render-table! app-ref {:column-names ["Name"]})
     (is (= [1] (selected-rows table))
         "New column names make the table model fire a structure change. Row 1 must stay selected.")))

 (deftest selection-survives-a-change-of-the-row-fn
   (let [app-ref (create-selection-table! {:rows [["a"] ["b"] ["c"]] :selection [1]})
         table (render-table! app-ref {})]
     (render-table! app-ref {:row-fn upper-case-row})
     (is (= "B" (on-edt (fn get-cell [] (.getValueAt table 1 0)))) "Precondition: the table uses the new row fn.")
     (is (= [1] (selected-rows table))
         "A new row fn makes the table model fire a structure change. Row 1 must stay selected.")))
