(ns retroact.swing.jlist
  (:require [clojure.tools.logging :as log]
            [retroact.toolkit :as tk])
  (:import (java.awt BorderLayout Color)
           (javax.swing DefaultListModel JList JPanel ListCellRenderer)))

(defn redraw-onscreen-component-substitute [& _])

(defn- create-cell-render-fn [render-fn cell-value index is-selected has-focus]
  (fn cell-render-fn [app-ref app-val]
    {:class    :panel
     :opaque   false
     :layout   {:class :border-layout}
     :contents [(assoc (render-fn app-ref app-val cell-value index is-selected has-focus)
                  :constraints BorderLayout/CENTER)]}))

; TODO: make this work for other components that use ListCellRenderer, like JComboBox
(defn create-retroact-cell-renderer [ctx render-fn]
  (let [update-component (:update-component ctx)
        ctx (assoc-in ctx [:app-val :retroact :toolkit-config :redraw-onscreen-component] `redraw-onscreen-component-substitute)
        _ (log/info "redraw-onscreen-component-substitute is" (get-in ctx [:app-val :retroact :toolkit-config :redraw-onscreen-component]))
        cell-panel-comp- (update-component ctx {:render (fn [app-ref app-val] {:class :panel})})
        cell-panel-comp (atom cell-panel-comp-)]
    (reify ListCellRenderer
      (getListCellRendererComponent [this list value index isSelected cellHasFocus]
        (swap! cell-panel-comp assoc :render (create-cell-render-fn render-fn value index isSelected cellHasFocus))
        (log/info "calling update-component from cell-renderer")
        (let [app-val (assoc-in @(:app-ref ctx)
                                [:retroact :toolkit-config :redraw-onscreen-component]
                                `redraw-onscreen-component-substitute)]
          (log/info "redraw-onscreen-component-substitute is" (get-in app-val [:retroact :toolkit-config :redraw-onscreen-component]))
          (reset! cell-panel-comp (update-component (assoc ctx :app-val app-val) @cell-panel-comp)))
        (log/info "returned from update-component from cell-renderer")
        (:onscreen-component @cell-panel-comp)))))

(defn- create-cell-renderer []
  (reify ListCellRenderer
    (getListCellRendererComponent [this list value index isSelected cellHasFocus]
      (let [model (.getModel list)
            child (.getElementAt model index)]
        (if isSelected
          (do
            (.setBackground child (.getSelectionBackground list))
            (.setForeground child (.getSelectionForeground list)))
          (do
            (.setBackground child (.getBackground list))
            (.setForeground child (.getForeground list))))
        (.setEnabled child (.isEnabled list))
        child))))

(defn create-jlist [{:keys [view] :as ctx}]
  (if-let [render-fn (:render view)]
    (let [jlist (JList. (DefaultListModel.))]
      (.setCellRenderer jlist (create-retroact-cell-renderer ctx render-fn))
      jlist)
    (let [jlist (JList. (DefaultListModel.))]
      (.setCellRenderer jlist (create-cell-renderer))
      jlist)))
