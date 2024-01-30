(ns retroact.swing.create-fns
  (:import (javax.swing JDialog JTextArea)
           (javax.swing.filechooser FileNameExtensionFilter)))

(defn create-jdialog [ui]
  (JDialog. (:owner ui)))

(defn create-file-name-extension-filter [ui]
  (FileNameExtensionFilter. (:description ui) (into-array String (:extensions ui))))

(defn create-jtext-area [ui]
  (let [text-area (JTextArea.)]
    (.setWrapStyleWord text-area true)
    text-area))