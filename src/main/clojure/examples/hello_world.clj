(ns examples.hello-world)

(defn hello-world-app
  []
  {:component-did-mount
   (fn component-did-mount [onscreen-component app-ref app-value]
     (.pack onscreen-component)
     (.setVisible onscreen-component true))
   :render
   (fn render [app-ref app-value]
     {:class      :frame
      :on-close   :dispose
      :contents   [{:class :label :text "Hello World!"}]
      })})
