(ns retroact.swing.compiled.listeners)

; Just a way to mark listeners as Retroact managed.
(definterface RetroactSwingListener)

; attr appliers grouped in the listeners :on-* section of swing.clj
(definterface RetroactSwingOnAction)
(definterface RetroactSwingOnChange)
(definterface RetroactSwingOnComponentResize)
(definterface RetroactSwingOnComponentHidden)
(definterface RetroactSwingOnComponentShown)
(definterface RetroactSwingOnKeyPressed)
(definterface RetroactSwingOnPropertyChange)
(definterface RetroactSwingOnSelectionChange)
(definterface RetroactSwingOnTextChange)
; no RetroactSwingOnSetValueAt because this is not a Java Swing listener, it is a Retroact specific fn.
(definterface RetroactSwingOnFocusGained)
(definterface RetroactSwingOnFocusLost)
(definterface RetroactSwingOnClick)
(definterface RetroactSwingOnMouseWheelMoved)
(definterface RetroactSwingOnDrag)
(definterface RetroactSwingOnDragOver)
(definterface RetroactSwingOnDrop)

; attr appliers not in the listener :on-* section.
(definterface RetroactSwingOnClose)
; no RetroactSwingOnVerticalScroll because it uses the :on-change for the vertical scrollbar
; no RetroactSwingOnHorizontalScroll because it uses the :on-change for the horizontal scrollbar
