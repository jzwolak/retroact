(ns retroact.swing.create-fns
  (:require [clojure.tools.logging :as log])
  (:import (java.awt Color FlowLayout Font Frame Window)
           (java.util Map)
           (javax.swing Box JDialog JOptionPane JTextArea OverlayLayout)
           (javax.swing.filechooser FileNameExtensionFilter)))

(defn create-color
  "Takes a value that is a Color object, a vector, or a valid arg to the Color constructor.
   For example: Color/WHITE, [255 255 255], 0xFFFFFF"
  [color]
  (cond
    (nil? color) nil
    (instance? Color color) color
    (and (vector? color) (= 3 (count color))) (Color. (nth color 0) (nth color 1) (nth color 2))
    (and (vector? color)) (Color. (nth color 0) (nth color 1) (nth color 2) (nth color 3))
    :else (Color. color)))

(defn create-font [font]
  (cond
    (nil? font) nil
    (instance? Font font) font
    (vector? font) (Font. (nth font 0) (nth font 1) (nth font 2))
    (map? font) (Font. ^Map font)
    :else (Font. font)))

(defn create-box-layout [{:keys [view]}]
  ; Retroact doesn't support BoxLayout because BoxLayout needs the parent in its constructor. Retroact doesn't support
  ; this as of this writing.
  (throw (Exception. "Cannot instantiate BoxLayout because Retroact doesn't support it currently since it needs its parent in its constructor."))
  #_(BoxLayout. ))

(defn create-jdialog [{:keys [view]}]
  (JDialog. (:owner view)))

(defn create-joption-pane [{:keys [view]}]
  (JOptionPane.
    (:message view)
    (:message-type view)
    (:option-type view)
    (:icon view)
    (if-let [options (:options view)]
      (into-array Object options)
      nil)
    (:initial-value view)))

(defn create-overlay-layout [{:keys [parent-onscreen-component] :as ctx}]
  (log/info "overlay layout got view:" (:view ctx))
  (log/info "overlay layout got parent:" (:parent-onscreen-component ctx))
  (OverlayLayout. parent-onscreen-component))

(defn create-file-name-extension-filter [{:keys [view]}]
  (FileNameExtensionFilter. (:description view) (into-array String (:extensions view))))

(defn create-horizontal-glue [ctx]
  (Box/createHorizontalGlue))

(defn create-jtext-area [ctx]
  (let [text-area (JTextArea.)]
    (.setWrapStyleWord text-area true)
    text-area))

(defn create-window [{:keys [view]}]
  (let [owner (:owner view)]
    (cond
      (instance? Frame owner) (Window. ^Frame owner)
      :else (Window. ^Window owner))))