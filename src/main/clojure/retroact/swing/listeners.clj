(ns retroact.swing.listeners
  (:require [clojure.tools.logging :as log]
            [retroact.swing.util :refer [silenced-events get-view get-comp-id]])
  (:import (java.awt.event ActionListener ComponentAdapter FocusAdapter KeyAdapter MouseAdapter MouseWheelListener WindowAdapter)
           (java.beans PropertyChangeListener)
           (javax.swing.event ChangeListener DocumentListener ListSelectionListener TreeSelectionListener)
           (retroact.swing.compiled.listeners RetroactSwingListener RetroactSwingOnAction RetroactSwingOnChange RetroactSwingOnClick RetroactSwingOnClose RetroactSwingOnComponentHidden RetroactSwingOnComponentResize RetroactSwingOnComponentShown RetroactSwingOnFocusGained RetroactSwingOnFocusLost RetroactSwingOnKeyPressed RetroactSwingOnMouseWheelMoved RetroactSwingOnPropertyChange RetroactSwingOnSelectionChange RetroactSwingOnTextChange)))

(defn- get-view-or-identity [c] (or (get-view c) c))

; Not finished...
(defmacro remove-listener
  "Removes Retroact managed listeners from component matching the class on-name (e.g., RetroactSwingOnClick) using
   methods derived from listener-name. For example, if listener-name has the value MouseListener and on-name has the
   value RetroactSwingOnClick, then getMouseListeners and removeMouseListener will be used for all listeners that are
   instances of RetroactSwingOnClick."
  [component listener-name on-name]
  (let [get-listeners (symbol (str "get" listener-name "s"))
        remove-listener (symbol (str "remove" listener-name))]
    `(doseq [~'listener (vec (. ~component ~get-listeners))]
         (when (instance? ~on-name ~'listener)
           (. ~component ~remove-listener ~'listener)))))


(defn proxy-action-listener
  [action-handler]
  (proxy [ActionListener RetroactSwingOnAction] []
    (actionPerformed [action-event]
      (action-handler action-event))))

(defn proxy-change-listener
  [change-handler]
  (proxy [ChangeListener RetroactSwingOnChange] []
    (stateChanged [change-event]
      (change-handler change-event))))

(defn proxy-component-resize-listener
  [component-resize-handler]
  (proxy [ComponentAdapter RetroactSwingOnComponentResize] []
    (componentResized [component-event]
      (component-resize-handler component-event))))

(defn proxy-component-hidden-listener
  [handler]
  (proxy [ComponentAdapter RetroactSwingOnComponentHidden] []
    (componentHidden [component-event]
      (handler component-event))))

(defn proxy-component-shown-listener
  [handler]
  (proxy [ComponentAdapter RetroactSwingOnComponentShown] []
    (componentShown [component-event]
      (handler component-event))))

(defn proxy-key-pressed-listener
  [handler]
  (proxy [KeyAdapter RetroactSwingOnKeyPressed] []
    (keyPressed [key-event]
      (handler key-event))))

(defn proxy-focus-gained [ctx focus-gained-handler]
  (proxy [FocusAdapter RetroactSwingOnFocusGained] []
    (focusGained [focus-event]
      (focus-gained-handler ctx focus-event))))

(defn proxy-focus-lost [ctx focus-lost-handler]
  (proxy [FocusAdapter RetroactSwingOnFocusLost] []
    (focusLost [focus-event]
      (focus-lost-handler ctx focus-event))))

(defn proxy-property-change-listener
  [property-change-handler]
  (proxy [PropertyChangeListener RetroactSwingOnPropertyChange] []
    (propertyChange [property-change-event]
      (property-change-handler property-change-event))))

; There are different selection listeners for different component types. Here they are.

(defn proxy-combo-box-selection-listener [ctx selection-change-handler]
  (proxy [ActionListener RetroactSwingOnSelectionChange] []
    (actionPerformed [action-event]
      (selection-change-handler action-event))))

(defn proxy-tree-selection-listener [ctx selection-change-handler]
  (proxy [TreeSelectionListener RetroactSwingOnSelectionChange] []
    (valueChanged [event]
      (let [onscreen-component (.getSource event)
            ; TODO: I should also pass in the event to the selection-change-handler because selected values don't
            ; indicate the path and the same value may be at multiple leafs.
            selected-values (mapv (fn [tree-path] (.getLastPathComponent tree-path)) (.getSelectionPaths onscreen-component))]
        (selection-change-handler (:app-ref ctx) (get-view onscreen-component) onscreen-component selected-values)))))

(defn proxy-list-selection-listener [ctx selection-change-handler]
  (proxy [ListSelectionListener RetroactSwingOnSelectionChange] []
    (valueChanged [event]
      (let [onscreen-component (.getSource event)
            selected-values (mapv get-view-or-identity (.getSelectedValuesList onscreen-component))]
        (when (not (.getValueIsAdjusting event))
          (selection-change-handler (:app-ref ctx) (get-view onscreen-component) onscreen-component selected-values))))))

(defn proxy-tree-list-selection-listener [ctx table selection-change-handler]
  (proxy [ListSelectionListener] []
    (valueChanged [event]
      (let [event-source (.getSource event)
            table-model (.getModel table)
            selected-indices (seq (.getSelectedIndices event-source))
            selected-values (mapv (fn get-item [i] (.getItemAt table-model i)) selected-indices)]
        (when (not (.getValueIsAdjusting event))
          (selection-change-handler (:app-ref ctx) (get-view table) table selected-values))))))

; end selection change listeners

(defn proxy-document-listener-to-text-change-listener [text-component text-change-handler]
  (proxy [DocumentListener RetroactSwingOnTextChange] []
    (changedUpdate [document-event]
      (if (contains? @silenced-events [(get-comp-id text-component) :text])
        (do)
        (do
          (log/info "DocumentListener.changedUpdate")
          (text-change-handler document-event))))
    (insertUpdate [document-event]
      (if (contains? @silenced-events [(get-comp-id text-component) :text])
        (do)
        (do
          (text-change-handler document-event))))
    (removeUpdate [document-event]
      (if (contains? @silenced-events [(get-comp-id text-component) :text])
        (do)
        (do
          (text-change-handler document-event))))))

(defn proxy-mouse-listener-click [app-ref click-handler]
  (proxy [MouseAdapter RetroactSwingOnClick] []
    (mousePressed [mouse-event]
      (click-handler app-ref mouse-event))
    (mouseClicked [mouse-event]
      (click-handler app-ref mouse-event))))

(defn proxy-mouse-listener-wheel-moved [ctx wheel-moved-handler]
  (proxy [MouseWheelListener RetroactSwingOnMouseWheelMoved] []
    (mouseWheelMoved [mouse-wheel-event]
      (wheel-moved-handler ctx mouse-wheel-event))))

; attr appliers not in the listener :on-* action section

(defn proxy-window-listener-close [app-ref onscreen-component handler]
  (proxy [WindowAdapter RetroactSwingOnClose] []
    (windowClosing [window-event]
      (handler app-ref onscreen-component window-event))))

