(ns examples.todo
  (:require [retroact.core :refer [init-app create-comp]]
            [clojure.tools.logging :as log])
  (:import (java.awt Color Insets)
           (javax.swing.border LineBorder)
           (javax.swing BorderFactory)))


(defn handle-new-todo-item [app-ref action-event]
  (let [new-item (-> action-event .getSource .getText)]
    (swap! app-ref update-in [:state]
           (fn [state]
             (let [todo-items (:todo-items state)]
               (-> state
                   (assoc :todo-items
                          (conj (vec todo-items) new-item))
                   ; this dissoc isn't necessary if I don't use handle-new-todo-item-text-change to track the new todo
                   ; item text in the app state. I'll keep it for now even though I'm not using that handler at the
                   ; moment.
                   (dissoc :new-todo-item-text)
                   #_(assoc :new-todo-item-text "")))))))


(defn handle-new-todo-item-text-change [app-ref new-text]
  (println "text changed: " new-text)
  (swap! app-ref update-in [:state]
         assoc :new-todo-item-text new-text)
  #_(swap! app-ref assoc :new-todo-item-text new-text #_:new-todo-item-caret-position #_(count new-text)))


(defn render-todo-item [todo-item]
  {:class :check-box
   :text  todo-item})


(defn todo-app
  []
  {:constructor         (fn constructor [props state]
                          (assoc state :new-todo-item-text "Hi!"))
   :component-did-mount (fn component-did-mount [onscreen-component app-ref app-value]
                          #_(.pack onscreen-component)
                          ; This is necessary for top level components like windows and dialogs. Unless the caller does
                          ; it for us.
                          (.setVisible onscreen-component true))
   :render
   (fn render [app-ref app-value]
     {:class      :frame
      :on-close   :dispose
      :background 0xffffff
      :layout     {:class              :mig-layout
                   :layout-constraints "fill"
                   :row-constraints    "[fill, grow 200][]"
                   :col-constraints    "[]"}
      :height     800
      :width      400
      :contents   [{:class       :list
                    :constraints "growx, wrap"
                    ;:background  0xedaacc
                    ;:border      (LineBorder. Color/BLUE 1 true)
                    :opaque      true
                    :contents    (vec (map render-todo-item (get-in app-value [:state :todo-items])))}

                   {:class          :text-field
                    :border         (BorderFactory/createCompoundBorder
                                      (LineBorder. Color/GRAY 1 true)
                                      (BorderFactory/createEmptyBorder 5 5 5 5))
                    ;:background     0xaaedcc
                    :text           (let [t (get-in app-value [:state :new-todo-item-text])]
                                      (log/info "rendering new todo item text as" t)
                                      t)
                    #_:caret-position #_(get-in app-value [:state :new-todo-item-caret-position])
                    :constraints    "growx"
                    ; tracking the text as it changes doesn't work. I get exceptions and the caret position isn't
                    ; preserved.
                    :on-text-change handle-new-todo-item-text-change
                    :on-action      handle-new-todo-item}]})

   })

(defn main []
  (let [app-ref (init-app (todo-app))]
    ; Create a second window if we like.
    #_(Thread/sleep 3000)
    #_(create-comp app-ref (todo-app))))
