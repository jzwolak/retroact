(ns retroact.swing.create-fns
  (:import (javax.swing JDialog JOptionPane JTextArea)
           (javax.swing.filechooser FileNameExtensionFilter)))

(defn create-jdialog [ui]
  (JDialog. (:owner ui)))

(defn create-joption-pane [ui]
  (JOptionPane.
    (:message ui)
    (:message-type ui)
    (:option-type ui)
    (:icon ui)
    (if-let [options (:options ui)]
      (into-array Object options)
      nil)
    (:initial-value ui)))

(defn create-file-name-extension-filter [ui]
  (FileNameExtensionFilter. (:description ui) (into-array String (:extensions ui))))

(defn create-jtext-area [ui]
  (let [text-area (JTextArea.)]
    (.setWrapStyleWord text-area true)
    text-area))