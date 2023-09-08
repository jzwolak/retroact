(ns retroact.swing.create-fns
  (:import (javax.swing JDialog)
           (javax.swing.filechooser FileNameExtensionFilter)))

(defn create-jdialog [ui]
  (JDialog. (:owner ui)))

(defn create-file-name-extension-filter [ui]
  (FileNameExtensionFilter. (:description ui) (into-array String (:extensions ui))))
