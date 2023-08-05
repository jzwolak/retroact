(ns examples.todo
  (:require [retroact.core :refer [init-app create-comp]]
            [clojure.tools.logging :as log]
            [retroact.swing :refer [get-view]])
  (:import (java.awt Color Insets)
           (javax.swing.border LineBorder)
           (javax.swing BorderFactory)))


(defn- gen-taskid [] (str (gensym "t")))


(defn handle-new-todo-item [app-ref action-event]
  (let [new-item (-> action-event .getSource .getText)]
    (swap! app-ref update-in [:state]
           (fn [state]
             (let [todo-items (:todo-items state)]
               (-> state
                   (assoc :todo-items
                          (conj (vec todo-items) {:id (gen-taskid) :text new-item}))
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


(defn- handle-todo-item-done
  [app-ref action-event]
  (log/info "running handle-todo-item-done")
  (let [source (.getSource action-event)
        done? (.isSelected source)
        view (get-view source)
        todo-item-id (get-in view [:todo-item :id])]
    (log/info "todo item checked" todo-item-id)
    (swap! app-ref update-in [:state :todo-items]
           (fn update-items [todo-items]
             (map (fn update-item [item]
                    (if (= (:id item) todo-item-id)
                      (assoc item :done? done?)
                      item))
                  todo-items)))))

(defn- handle-test-checkbox
  [app-ref action-event]
  (log/info "got checkbox action event"))

(def todo-table-attr-map [:done? :text])
(def todo-table-cell-fns [boolean identity])

(defn- todo-table-row-fn [item]
  (mapv #(%2 (%1 item)) todo-table-attr-map todo-table-cell-fns))

(defn- handle-set-value-at
  "old-item is the complete item, the entire row. new-value is just the value at the particular cell that was changed."
  [app-ref view old-item new-value row col]
  (log/info "setting value at" row "," col "to" new-value)
  (let [todo-item-id (:id old-item)
        attr (nth todo-table-attr-map col)]
    (swap! app-ref update-in [:state :todo-items]
           (fn find-and-update-item [todo-items]
             (map (fn update-item [item]
                    (if (= (:id item) todo-item-id)
                      (assoc item attr new-value)
                      item))
                  todo-items)))))


(defn render-todo-item [todo-item]
  {:class     :check-box
   :todo-item todo-item
   :on-action handle-todo-item-done
   :selected  (:done? todo-item)
   :text      (:text todo-item)})


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
      :contents   [{:class       :table
                    :constraints "growx, wrap"
                    ;:background  0xedaacc
                    ;:border      (LineBorder. Color/BLUE 1 true)
                    :opaque      true
                    ;:contents    (vec (map render-todo-item (get-in app-value [:state :todo-items])))
                    ; TODO: consider creating table model each time spec is modified for how table model should access
                    ; data. Then, have table model listen to watch on atom and check if data has changed.
                    :table-model {:data-fn (fn [app-ref])}
                    :table-data  (vec (get-in app-value [:state :todo-items]))
                    :row-fn      todo-table-row-fn #_(fn [item] [(boolean (:done? item)) (:text item)])
                    :row-editable-fn (fn [item] [true true])
                    :on-set-value-at handle-set-value-at
                    }

                   {:class       :check-box
                    :constraints "growx, wrap"
                    :on-action   handle-test-checkbox
                    :text        "Test"}
                   {:class          :text-field
                    ; TODO: this returns a different object each time which means the result of render is different
                    ; even though the state may be the same. render fns and Retroact work best when render fns are pure.
                    ; Which means, same inputs, same outputs.
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
                    :on-action      handle-new-todo-item
                    #_(fn [app-ref action-event] (handle-new-todo-item app-ref action-event))}]})

   })

(defn main []
  (let [app-ref (init-app (todo-app))]
    ; Create a second window if we like.
    #_(Thread/sleep 3000)
    #_(create-comp app-ref (todo-app))))
