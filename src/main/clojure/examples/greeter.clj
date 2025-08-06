(ns examples.greeter
  (:require [clojure.tools.logging :as log])
  (:import (javax.swing SwingUtilities)))

(defn say-hi-action-handler
  [app-ref action-event]
  (swap! app-ref update-in [:state]
         (fn [state]
           (if (= "Yo" (:greeting state))
             (assoc state :greeting "Dog" :background 0x4488ff :expanded true)
             (assoc state :greeting "Yo" :background 0x00ffff :expanded true)))))

(defn greeter-app
  []
  {:constructor
   (fn constructor [props state]
     ; return unmodified app-state. Required and a best practice because other components may depend on the state.
     state)
   ; TODO: possibly generify componentDidMount to be an event in response to state change. Code may need to execute when
   ; the state changes to pack again or revalidate or any number of other things. And the code may need to execute only
   ; if certain parts of the state changed. Like `pack()` may only execute if visibility goes from false to true. Having
   ; a way to handle this concisely and generically would be amazing!
   :component-did-mount
   (fn component-did-mount [onscreen-component app-ref app-value]
     (println "greeter app did mount")
     (SwingUtilities/invokeLater
       #(do
          (.pack onscreen-component)
          (.setLocationRelativeTo onscreen-component nil)
          (.setVisible onscreen-component true))))
   ; Called when a child is added, removed or "swapped". A swap is when a child is removed and a new one is added in its
   ; place.
   ; TODO: this is not implemented yet.
   ; Or maybe I want to associate a pack action with the actual addition of the child in a way where it's only run if
   ; the child is actually added. Can I add transition "triggers" kind of like in SBML... when the state transitions
   ; from one to another.
   :children-changed
   (fn children-changed [component app-ref app-value]
     (SwingUtilities/invokeLater
       #(do
          (.pack component))))
   ; TODO: I can create fns to make these maps more expressive. As in:
   ; (frame {:background 0xff0000 :on-close :dispose :layout (mig-layout "flowy)}
   ;        (label (or (:greeting @app-state) "Hello World!"))
   ;        (button "Say Hi!" (fn say-hi [action-event] (swap! app-state assoc :greeting "Yo"))))
   :render
   (fn render [app-ref app-value]
     {:class      :frame
      :background (or (get-in app-value [:state :background]) 0xff0000)
      :opaque     true
      :on-close   :dispose
      :layout     {:class              :mig-layout
                   :layout-constraints "flowy"}
      :contents   (let [contents [{:class :label
                                   :text  (or (get-in app-value [:state :greeting]) "Hello World!")}
                                  {:class     :button
                                   :text      "Say Hi!"
                                   ; named fn is necessary to avoid update every time with anonymous fn
                                   :on-action say-hi-action-handler}]]
                    (if (get-in app-value [:state :expanded])
                      (conj contents {:class :label :text "Expanded!"})
                      contents))
      })})
