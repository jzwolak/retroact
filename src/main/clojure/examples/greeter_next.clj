(ns examples.greeter-next
  (:require [retroact.swing-next :as sn]
            [retroact.next :as n])
  (:import (java.awt Color)
           (javax.swing JButton JFrame JLabel JPanel)
           (net.miginfocom.swing MigLayout)))

(defn say-hi-action-handler
  [app-ref action-event]
  (swap! app-ref update-in [:state]
         (fn [state]
           (if (= "Yo" (:greeting state))
             (assoc state :greeting "Dog" :background 0x4488ff :expanded true)
             (assoc state :greeting "Yo" :background 0x00ffff :expanded true)))))

(defn greeter-app
  []
  {#_:constructor
   #_(fn constructor [props state]
       ; return unmodified app-state. Required and a best practice because other components may depend on the state.
       state)
   ; TODO: possibly generify componentDidMount to be an event in response to state change. Code may need to execute when
   ; the state changes to pack again or revalidate or any number of other things. And the code may need to execute only
   ; if certain parts of the state changed. Like `pack()` may only execute if visibility goes from false to true. Having
   ; a way to handle this concisely and generically would be amazing!
   ; TODO: when writing the LAF this may be abstracted out. The LAF is the Retroact look and feel that will change how
   ; Swing behaves from a programmatic level to the developer so the developer can use it more easily.
   :component-did-mount
   (fn component-did-mount [onscreen-component app-ref app-value]
     (println "greeter app did mount")
     (.pack onscreen-component)
     (.setVisible onscreen-component true))
   ; Called when a child is added, removed or "swapped". A swap is when a child is removed and a new one is added in its
   ; place.
   ; TODO: this is not implemented yet.
   ; Or maybe I want to associate a pack action with the actual addition of the child in a way where it's only run if
   ; the child is actually added. Can I add transition "triggers" kind of like in SBML... when the state transitions
   ; from one to another.
   #_:children-changed
   #_(fn children-changed [component app-ref app-value]
       (.pack component))
   ; TODO: I can create fns to make these maps more expressive. As in:
   ; (frame {:background 0xff0000 :on-close :dispose :layout (mig-layout "flowy)}
   ;        (label (or (:greeting @app-state) "Hello World!"))
   ;        (button "Say Hi!" (fn say-hi [action-event] (swap! app-state assoc :greeting "Yo"))))
   :render
   (fn render [app-ref app-value]
     {:class      JFrame
      :properties {
                   ;:opaque     true
                   ;:on-close   :dispose
                   :contentPane {:class      JPanel
                                 :properties {:layout     {:class      MigLayout
                                                           :properties {:layoutConstraints "flowy"}}
                                              :opaque     true
                                              :background {:class            Color
                                                           :constructor-args [[Integer/TYPE (int (or (get-in app-value [:state :background]) 0xff0000))]]}
                                              :components (let [contents [{:class      JLabel
                                                                           :properties {:text (or (get-in app-value [:state :greeting]) "Hello World!")}}
                                                                          {:class      JButton
                                                                           :properties {:text "Say Hi!"}
                                                                           ; named fn is necessary to avoid update every time with anonymous fn
                                                                           ;:on-action say-hi-action-handler
                                                                           :listeners {:actionListener {:actionPerformed say-hi-action-handler}}
                                                                           }]]
                                                            (if (get-in app-value [:state :expanded])
                                                              (conj contents {:class      JLabel
                                                                              :properties {:text "Expanded!"}})
                                                              contents))}}}
      })})

(defn main []
  (-> (atom {})
      (n/create {:toolkit-bindings sn/toolkit-bindings})
      (n/create-comp (greeter-app))))
