(ns retroact.swing.create-fns
  (:import (java.awt Frame Window)
           (javax.swing Box JDialog JOptionPane JTextArea)
           (javax.swing.filechooser FileNameExtensionFilter)))

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