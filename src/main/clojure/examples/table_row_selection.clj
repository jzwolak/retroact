(ns examples.table-row-selection
  (:require [retroact.core :refer [init-app]]
            [retroact.swing :refer [retroact-initiated?]]
            [clojure.tools.logging :as log])
  (:import (java.awt BorderLayout Color)
           (javax.swing BorderFactory SwingUtilities)))

;; Sample data for the table
(def sample-people
  [{:id 1 :name "Alice Johnson" :age 28 :city "New York"}
   {:id 2 :name "Bob Smith" :age 34 :city "Los Angeles"}
   {:id 3 :name "Carol Davis" :age 22 :city "Chicago"}
   {:id 4 :name "David Wilson" :age 45 :city "Houston" :member true}
   {:id 5 :name "Eve Brown" :age 31 :city "Phoenix"}
   {:id 6 :name "Frank Miller" :age 29 :city "Philadelphia" :member true}
   {:id 7 :name "Grace Lee" :age 26 :city "San Antonio" :member true}
   {:id 8 :name "Henry Taylor" :age 38 :city "San Diego"}])

;; Define how to get the item at row. Note the item is the app's internal representation and
;; does not correspond to the data in the row in the table. For that data, use the row-fn.
(defn get-item-at-fn [[items selection] row]
  (nth items row))

;; Define how to extract row data from each person
(defn person-row-fn [person]
  [(:name person) (:age person) (:city person)])

;; Handle selection changes (both programmatic and mouse clicks)
(defn handle-selection-change [app-ref view component selected-values]
  (when (not (retroact-initiated?))
    (let [selected-rows (.getSelectedRows component)]
      (log/info "Selection changed. Selected rows:" (vec selected-rows))
      (if (empty? selected-rows)
        (swap! app-ref assoc-in [:state :selected-row-interval] nil)
        (let [min-row (apply min selected-rows)
              max-row (apply max selected-rows)]
          (swap! app-ref assoc-in [:state :selected-row-interval] [min-row max-row]))))))

;; Handle button clicks to select specific rows
(defn select-first-person [app-ref _]
  (log/info "Selecting first person")
  (swap! app-ref assoc-in [:selection] [0]))

(defn select-first-three [app-ref _]
  (log/info "Selecting first three people")
  (swap! app-ref assoc-in [:selection] [0 1 2]))

(defn clear-selection [app-ref _]
  (log/info "Clearing selection")
  (swap! app-ref assoc-in [:selection] []))

(defn selection-fn [table-data]
  (nth table-data 1))

(defn table-row-selection-app
  []
  {:component-did-mount
   (fn component-did-mount [onscreen-component app-ref app-value]
     (SwingUtilities/invokeLater
       #(do
          (.pack onscreen-component)
          (.setLocationRelativeTo onscreen-component nil)
          (.setVisible onscreen-component true))))

   :render
   (fn render [app-ref app-value]
     (let [selected-interval (get-in app-value [:state :selected-row-interval])
           selected-people (when selected-interval
                             (let [[start end] selected-interval]
                               (subvec sample-people start (inc end))))]
       {:class      :frame
        :title      "Table Row Selection Example"
        :on-close   :dispose
        :background Color/WHITE
        :layout     {:class :border-layout}
        :width      700
        :height     500
        :contents   [{:class       :panel
                      :constraints BorderLayout/NORTH
                      :background  Color/LIGHT_GRAY
                      :border      (BorderFactory/createEmptyBorder 10 10 10 10)
                      :layout      {:class              :mig-layout
                                    :layout-constraints "fill"
                                    :col-constraints    "[grow][]"}
                      :contents    [{:class       :label
                                     :constraints "growx"
                                     :text        (str "Selection interval: " selected-interval
                                                       (when selected-people
                                                         (str " | Selected: "
                                                              (clojure.string/join ", " (map :name selected-people)))))}
                                    {:class       :panel
                                     :constraints "wrap"
                                     :layout      {:class :mig-layout}
                                     :contents    [{:class     :button
                                                    :text      "Select First"
                                                    :on-action select-first-person}
                                                   {:class     :button
                                                    :text      "Select First 3"
                                                    :on-action select-first-three}
                                                   {:class     :button
                                                    :text      "Clear Selection"
                                                    :on-action clear-selection}]}]}

                     {:class         :scroll-pane
                      :constraints   BorderLayout/CENTER
                      :viewport-view {:class                  :table
                                      :table-data             [sample-people (:selection app-value)]
                                      :get-item-at-fn         get-item-at-fn
                                      :row-fn                 person-row-fn
                                      :column-names           ["Name" "Age" "City"]
                                      :selection-fn           selection-fn
                                      ;:row-selection-interval selected-interval
                                      :on-selection-change    handle-selection-change}}]}))})

(defn main []
  (let [app-ref (init-app (table-row-selection-app))]
    (log/info "Table row selection example started")))
