(ns retroact.swing-next
  (:import (java.awt Container)))

(defmulti add-child-at (fn [container child child-view index] (class container)))
(defmulti remove-child-at (fn [container index] (class container)))
(defmulti get-child-at (fn [container index] (class container)))


;TODO: what I want to do here is have all these functions resolve to data that at some later point can execute.
; Then, the (:constraints child-view) can build an object, as necessary, of the layout constraints type.
(defmethod add-child-at Container [container child child-view index]
  (.add ^Container container child (:constraints child-view) ^int index))
(defmethod remove-child-at Container [container index] (.remove container ^int index))
(defmethod get-child-at Container [container index] (.getComponent container ^int index))



; TODO: there's no reason to create this map to lookup add/remove methods. Just create the multimethod and have the
; Container class as a route for the multimethod. Then register the multimethod as a collection adder/remover for
; Swing.

; The look-and-feel changes the way, from the coding perspective, how Swing behaves. This is so the developer can have
; a different experience. This ultimately will affect how Swing behaves to the user - at least the default behavior.
; :classes - a map of class specific modifications keyed on the class.
;   :properties - a map of property specific modifications for properties on the class. Use the keyword form of the
;                 property name, just as it appears in the view.
;                 Collection properties may have the add/remove method names and signatures specified. This is useful
;                 when the Java Bean is not an indexed property, as is the case for Container - unfortunately.
; I don't use :classes or :properties at all, but I'll leave the docs for now because it's an idea that may have value.
(def toolkit-bindings
  {:defaults {:add-child-at add-child-at
              :remove-child-at remove-child-at
              :get-child-at get-child-at}})
