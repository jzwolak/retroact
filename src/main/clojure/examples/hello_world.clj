(ns examples.hello-world)

(defn hello-world-app
  []
  {:component-did-mount
   (fn component-did-mount [onscreen-component comp-id app-ref app-value]
     (.pack onscreen-component)
     (.setVisible onscreen-component true))
   :render
   (fn render [comp-id app-ref app-value]
     {:class      :frame
      :on-close   :dispose
      :contents   [{:class :label :text "Hello World!"}]
      })})
