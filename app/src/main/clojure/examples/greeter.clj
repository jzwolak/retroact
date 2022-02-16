(ns examples.greeter)

(defn greeter-app
  []
  {:constructor
   (fn constructor [props]
     ; return initial app-state. Not necessary if it's an empty map, but I'll put it here.
     {})
   ; TODO: possibly generify componentDidMount to be an event in response to state change. Code may need to execute when
   ; the state changes to pack again or revalidate or any number of other things. And the code may need to execute only
   ; if certain parts of the state changed. Like `pack()` may only execute if visibility goes from false to true. Having
   ; a way to handle this concisely and generically would be amazing!
   :component-did-mount
   (fn component-did-mount [component app-ref app-value]
     (.pack component)
     (.setVisible component true))
   ; Called when a child is added, removed or "swapped". A swap is when a child is removed and a new one is added in its
   ; place.
   ; TODO: this is not implemented yet.
   :children-changed
   (fn children-changed [component app-ref app-value]
     (.pack component))
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
                                   ; TODO: this causes an update every time because it generates a new anonymous fn. Think about how to
                                   ; avoid the update every time. A named fn would probably be better, but then how do we get state?
                                   :on-action (fn say-hi [action-event]
                                                (swap! app-ref update-in [:state]
                                                       (fn [state]
                                                         (if (= "Yo" (:greeting state))
                                                           (assoc state :greeting "Dog" :background 0x4488ff :expanded true)
                                                           (assoc state :greeting "Yo" :background 0x00ffff :expanded true)))
                                                       ))}]]
                    (if (get-in app-value [:state :expanded])
                      (conj contents {:class :label :text "Expanded!"})
                      contents))
      })})
