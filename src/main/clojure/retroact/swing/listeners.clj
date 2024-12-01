(ns retroact.swing.listeners
  (:require [clojure.tools.logging :as log]
            [retroact.swing.util :refer [silenced-events get-view get-comp-id]])
  (:import (java.awt.event ActionListener ComponentAdapter FocusAdapter MouseAdapter MouseWheelListener WindowAdapter)
           (java.beans PropertyChangeListener)
           (javax.swing.event ChangeListener DocumentListener ListSelectionListener TreeSelectionListener)
           (retroact.swing.compiled.listeners RetroactSwingListener)))

(defn- get-view-or-identity [c] (or (get-view c) c))

(defn proxy-action-listener
  [action-handler]
  (proxy [ActionListener RetroactSwingListener] []
    (actionPerformed [action-event]
      (action-handler action-event))))

(defn reify-change-listener
  [change-handler]
  (reify ChangeListener
    (stateChanged [this change-event]
      (change-handler change-event))))

(defn reify-component-resize-listener
  [component-resize-handler]
  (proxy [ComponentAdapter] []
    (componentResized [component-event]
      (component-resize-handler component-event))))

(defn reify-component-hidden-listener
  [handler]
  (proxy [ComponentAdapter] []
    (componentHidden [component-event]
      (handler component-event))))

(defn reify-property-change-listener
  [property-change-handler]
  (reify PropertyChangeListener
    (propertyChange [this property-change-event]
      (property-change-handler property-change-event))))

(defn proxy-window-listener-close [app-ref onscreen-component handler]
  (proxy [WindowAdapter] []
    (windowClosing [window-event]
      (handler app-ref onscreen-component window-event))))

(defn proxy-mouse-listener-click [app-ref click-handler]
  (proxy [MouseAdapter RetroactSwingListener] []
    (mousePressed [mouse-event]
      (click-handler app-ref mouse-event))
    (mouseClicked [mouse-event]
      (click-handler app-ref mouse-event))))

(defn reify-mouse-listener-wheel-moved [ctx wheel-moved-handler]
  (reify MouseWheelListener
    (mouseWheelMoved [this mouse-wheel-event]
      (wheel-moved-handler ctx mouse-wheel-event))))

(defn proxy-focus-gained [ctx focus-gained-handler]
  (proxy [FocusAdapter] []
    (focusGained [focus-event]
      (focus-gained-handler ctx focus-event))))

(defn proxy-focus-lost [ctx focus-lost-handler]
  (proxy [FocusAdapter] []
    (focusLost [focus-event]
      (focus-lost-handler ctx focus-event))))

(defn reify-tree-selection-listener [ctx selection-change-handler]
  (reify TreeSelectionListener
    (valueChanged [this event]
      (let [onscreen-component (.getSource event)
            ; TODO: I should also pass in the event to the selection-change-handler because selected values don't
            ; indicate the path and the same value may be at multiple leafs.
            selected-values (mapv (fn [tree-path] (.getLastPathComponent tree-path)) (.getSelectionPaths onscreen-component))]
        (selection-change-handler (:app-ref ctx) (get-view onscreen-component) onscreen-component selected-values)))))

(defn reify-list-selection-listener [ctx selection-change-handler]
  (reify ListSelectionListener
    (valueChanged [this event]
      (let [onscreen-component (.getSource event)
            selected-values (mapv get-view-or-identity (.getSelectedValuesList onscreen-component))]
        (when (not (.getValueIsAdjusting event))
          (selection-change-handler (:app-ref ctx) (get-view onscreen-component) onscreen-component selected-values))))))

(defn reify-tree-list-selection-listener [ctx table selection-change-handler]
  (reify ListSelectionListener
    (valueChanged [this event]
      (let [event-source (.getSource event)
            table-model (.getModel table)
            selected-indices (seq (.getSelectedIndices event-source))
            selected-values (mapv (fn get-item [i] (.getItemAt table-model i)) selected-indices)]
        (when (not (.getValueIsAdjusting event))
          (selection-change-handler (:app-ref ctx) (get-view table) table selected-values))))))

(defn reify-document-listener-to-text-change-listener [text-component text-change-handler]
  (reify DocumentListener
    (changedUpdate [this document-event]
      (if (contains? @silenced-events [(get-comp-id text-component) :text])
        (do)
        (do
          (log/info "DocumentListener.changedUpdate")
          (text-change-handler document-event))))
    (insertUpdate [this document-event]
      (if (contains? @silenced-events [(get-comp-id text-component) :text])
        (do)
        (do
          (text-change-handler document-event))))
    (removeUpdate [this document-event]
      (if (contains? @silenced-events [(get-comp-id text-component) :text])
        (do)
        (do
          (text-change-handler document-event))))))
