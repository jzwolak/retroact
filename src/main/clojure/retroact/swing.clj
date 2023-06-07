(ns retroact.swing
  (:require [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :as log]
            [retroact.swing.jlist :refer [create-jlist]]
            [retroact.swing.jtable :refer [create-jtable safe-table-model-set]])
  (:import (java.awt Color Component Container Dimension BorderLayout)
           (java.awt.event ActionListener)
           (javax.swing JButton JCheckBox JFrame JLabel JList JPanel JScrollPane JTextField JComponent JTable)
           (javax.swing.event DocumentListener ListSelectionListener)
           (net.miginfocom.swing MigLayout)))


(def client-prop-prefix "retroact-")

(defn redraw-onscreen-component [c]
  (.revalidate c))

(defmulti set-client-prop (fn [comp name value] (class comp)))
(defmethod set-client-prop JComponent [comp name value]
  (.putClientProperty comp (str client-prop-prefix name) value))
(defmethod set-client-prop Object [comp name value]
  ; ignore. Or possibly in the future use an internal map of (map comp -> ( map name -> value) )
  ; Which would require removing things from the map when they are removed from the view. WeakReference may help with
  ; this.
  )

(defmulti get-client-prop (fn [comp name] (class comp)))
(defmethod get-client-prop JComponent [comp name]
  (let [val
        (.getClientProperty comp (str client-prop-prefix name))]
    val))
(defmethod get-client-prop Object [comp name]
  ; ignore
  )

(defn assoc-view [onscreen-component view]
  (set-client-prop onscreen-component "view" view))

(defn get-view [onscreen-component]
  (loop [oc onscreen-component]
    (let [view (if oc (get-client-prop oc "view"))]
      (cond
        view view
        (nil? oc) nil
        :else (recur (.getParent oc))))))

(defn- get-view-or-identity [c] (or (get-view c) c))

(def on-close-action-map
  {:dispose JFrame/DISPOSE_ON_CLOSE
   :do-nothing JFrame/DO_NOTHING_ON_CLOSE
   :exit JFrame/EXIT_ON_CLOSE
   :hide JFrame/HIDE_ON_CLOSE})

(defn reify-action-listener [action-handler]
  (reify ActionListener
    (actionPerformed [this action-event]
      (action-handler action-event))))

(defn reify-list-selection-listener [ctx selection-change-handler]
  (reify ListSelectionListener
    (valueChanged [this event]
      (let [onscreen-component (.getSource event)
            selected-values (mapv get-view-or-identity (.getSelectedValuesList onscreen-component))]
        (log/info "selection event (list) =" event)
        (when (not (.getValueIsAdjusting event))
          (selection-change-handler (:app-ref ctx) (get-view onscreen-component) onscreen-component selected-values))))))

(defn reify-tree-list-selection-listener [ctx table selection-change-handler]
  (reify ListSelectionListener
    (valueChanged [this event]
      (let [event-source (.getSource event)
            table-model (.getModel table)
            selected-indices (seq (.getSelectedIndices event-source))
            _ (log/info "selected-indices = " selected-indices)
            selected-values (mapv (fn get-item [i] (.getItemAt table-model i)) selected-indices)]
        (log/info "selected-values = " selected-values)
        (log/info "selection event (tree) =" event)
        (when (not (.getValueIsAdjusting event))
          (selection-change-handler (:app-ref ctx) (get-view table) table selected-values))))))

(defn reify-document-listener-to-text-change-listener [text-change-handler]
  (reify DocumentListener
    (changedUpdate [this document-event] (text-change-handler document-event))
    (insertUpdate [this document-event] (text-change-handler document-event))
    (removeUpdate [this document-event] (text-change-handler document-event))))

(defn set-width [c ctx width]
  (let [height (-> c .getSize .getHeight)]
    (.setSize c (Dimension. width height))))

(defn set-height [c ctx height]
  (let [width (-> c .getSize .getWidth)]
    (.setSize c (Dimension. width height))))


; :contents fns
(defmulti get-existing-children class)
(defmethod get-existing-children Container [c] (.getComponents c))
(defmethod get-existing-children JFrame [c] (.getComponents (.getContentPane c)))
(defmethod get-existing-children JList [jlist]
  (let [model (.getModel jlist)]
    (log/info "JList get-existing-children not implemented")
    (-> model
        (.elements)
        (enumeration-seq)
        (vec))))

(defmulti add-new-child-at (fn [container child _ _] (class container)))
(defmethod add-new-child-at Container [^Container c ^Component child constraints index] (.add ^Container c child constraints ^int index))
(defmethod add-new-child-at JFrame [^Container c ^Component child constraints index] (.add ^Container (.getContentPane c) child constraints ^int index))
(defmethod add-new-child-at JList [^JList jlist ^Component child constraints index]
  (let [model (.getModel jlist)]
    (println "jlist add-new-child-at" index "(model class:" (class model) " size =" (.getSize model) ")" child)
    (.add model index child)
    child))

(defmulti remove-child-at (fn [container index] (class container)))
(defmethod remove-child-at Container [c index] (.remove c index))
(defmethod remove-child-at JFrame [c index] (.remove (.getContentPane c) index))
(defmethod remove-child-at JList [jlist index] (println "JList remove-child-at not implemented yet"))

(defmulti get-child-at (fn [container index] (class container)))
(defmethod get-child-at Container [c index] (.getComponent c index))
(defmethod get-child-at JFrame [c index] (.getComponent (.getContentPane c) index))
(defmethod get-child-at JList [jlist index]
  (let [child
        (-> jlist
            (.getModel)
            (.getElementAt index))]
    (println "jlist get-child-at:" child)
    child))
; end :contents fns


(defmulti get-child-data (fn [onscreen-component index] (class onscreen-component)))
(defmethod get-child-data JList [onscreen-component index]
  (let [child (get-child-at onscreen-component index)]
    (get-client-prop child "data")))


; TODO: oops... I just added all the :class key-value pairs, but perhaps unnecessarily. I did that so I could match
; the children to the virtual dom, but I don't need to do that. The diff will be between two virtual doms. After the
; diff is complete I should have a list of deletions, insertions, and changes (apply attributes) at particular indices.
; I won't need to look at the class or identity of the actual components, I can just remove the necessary indices, add
; the necessary indices, and update attributes.
(def class-map
  {:default       (fn default-swing-component-constructor [ui]
                    (log/warn "using default constructor to generate a JPanel")
                    (JPanel.))
   :border-layout BorderLayout
   :button        JButton
   :check-box     JCheckBox
   :frame         JFrame
   :label         JLabel
   :list          create-jlist
   :mig-layout    MigLayout
   :panel         JPanel
   :table         create-jtable
   :text-field    JTextField})

(defn text-changed? [old-text new-text]
  (not
    (or (= old-text new-text)
        ; empty string and nil are treated the same. See JTextComponent.setText().
        (and (nil? old-text) (empty? new-text))
        (and (empty? old-text) (nil? new-text)))))

(defn set-column-names
  [c ctx column-names]
  (safe-table-model-set c (memfn setColumnNames column-names) column-names))

(defn set-row-fn
  "Set the row fn on the table model of a JTable. The row fn takes the nth element in the data vector and returns a
  vector representing the columns in that row."
  [c ctx row-fn]
  (safe-table-model-set c (memfn setRowFn row-fn) row-fn))

(defn set-data
  "Set the data for a JTable in the table model. The data is a vector of arbitrary objects, one per row. Use set-row-fn
   to define how to translate those objects into columns."
  [c ctx data]
  (let [v-data (vec data)]
    (safe-table-model-set c (memfn setData v-data) v-data))
  #_(when (instance? JTable c)
    (let [model (.getModel c)]
      (.setData model (vec data)))))

(defn set-row-editable-fn
  "Similar to set-row-fn but returns a vec of true or false representing whether each column in the row is editable. A
  value of true means the column for this row is editable. The row-editable-fn takes a single arg, which is the nth
   element of the table model data vector representing the nth row."
  [c ctx row-editable-fn]
  (safe-table-model-set c (memfn setRowEditableFn row-editable-fn) row-editable-fn)
  #_(when (instance? JTable c)
    (let [model (.getModel c)]
      (.setRowEditableFn model row-editable-fn))))

(defmulti on-selection-change (fn [c _ _] (class c)))

(defmethod on-selection-change JList [c ctx selection-change-handler]
  (doseq [sl (vec (.getListSelectionListeners c))] (.removeListSelectionListener c sl))
  (.addListSelectionListener c (reify-list-selection-listener ctx selection-change-handler)))

(defmethod on-selection-change JTable [table ctx selection-change-handler]
  (let [selection-model (.getSelectionModel table)]
    (doseq [sl (vec (.getListeners table ListSelectionListener))] (.removeListSelectionListener selection-model sl))
    (.addListSelectionListener selection-model (reify-tree-list-selection-listener ctx table selection-change-handler))))

(defmethod on-selection-change JScrollPane [c ctx selection-change-handler]
  (on-selection-change (.getView (.getViewport c)) ctx selection-change-handler))

(defn on-set-value-at
  "The set-value-at-handler has args [app-ref old-item new-value row col] where row and col are the row and column of
   the table where the user performed an edit on a the cell at row and column, old-item is the item represented at that
   row (this is in domain space and may be a map, vec, set, or any arbitrary data structure - maps are typical), and
   new-value is the value of the cell after the user completed the edit. The set-value-at-handler must determine where
   in the item data structure the new-value must be set and how to perform that set in app-ref. This is, in a sense the
   inverse of the row-fn in set-row-fn. See examples.todo for an example handler."
  [c ctx set-value-at-handler]
  (safe-table-model-set c (memfn setSetValueAtFn set-value-at-fn)
                        (fn set-value-at-fn [old-item new-value row col]
                          (set-value-at-handler (:app-ref ctx) (get-view c) old-item new-value row col))))

(def attr-appliers
  {:background          (fn set-background [c ctx color] (cond
                                                           (instance? JFrame c) (.setBackground (.getContentPane c) (Color. color))
                                                           :else (.setBackground c (Color. color))))
   :border              (fn set-border [c ctx border] (.setBorder c border))
   :data                (fn set-retroact-data [c ctx data] (set-client-prop c "data" data))
   ; The following doesn't appear to work. It may be that macOS overrides margins. Try using empty border.
   :margin              (fn set-margin [c ctx insets] (.setMargin c insets))
   :height              set-height
   :layout              {:set (fn set-layout [c ctx layout] (.setLayout c layout))
                         :get (fn get-layout [c ctx]
                                (cond
                                  (instance? JFrame c) (.getLayout (.getContentPane c))
                                  :else (.getLayout c)))}
   :opaque              (fn set-opaque [c ctx opaque] (cond
                                                        (instance? JFrame c) (.setOpaque (.getContentPane c) opaque)
                                                        :else (.setOpaque c opaque)))
   :selected            (fn set-selected [c ctx selected?]
                          (.setSelected c selected?))
   :selection-mode      (fn set-selection-mode [c ctx selection-mode] (.setSelectionMode ^JList c selection-mode))
   :text                (fn set-text [c ctx text]
                          #_(.printStackTrace (Exception. "stack trace"))
                          (let [old-text (.getText c)]
                            (log/info (str "new-text = \"" text "\" old-text = \"" old-text "\""))
                            (log/info (str "new-text nil? " (nil? text) " old-text nil? " (nil? old-text)))
                            (when (text-changed? old-text text)
                              (.setText c text))))
   :width               set-width
   :caret-position      (fn set-caret-position [c ctx position] (.setCaretPosition c position))
   ; TODO: if action not in on-close-action-map, then add it as a WindowListener to the close event
   :on-close            (fn on-close [c ctx action] (.setDefaultCloseOperation c (on-close-action-map action)))
   :layout-constraints  (fn set-layout-constraints [c ctx constraints] (.setLayoutConstraints c constraints))
   :row-constraints     (fn set-row-constraints [c ctx constraints] (.setRowConstraints c constraints))
   :column-constraints  (fn set-column-constraints [c ctx constraints] (.setColumnConstraints c constraints))
   ; Table attr appliers
   :column-names        set-column-names
   :row-editable-fn     set-row-editable-fn
   :row-fn              set-row-fn
   :table-data          set-data

   ; All action listeners must be removed before adding the new one to avoid re-adding the same anonymous fn.
   :on-action           (fn on-action [c ctx action-handler]
                          (log/info "registering action listener for component: " c)
                          #_(if (instance? JCheckBox c) (action-handler (:app-ref ctx) nil))
                          (doseq [al (vec (.getActionListeners c))]
                            (log/info "removing action listener" al " for " c)
                            (.removeActionListener c al))
                          (.addActionListener c (reify-action-listener (fn action-handler-clojure [action-event]
                                                                         (action-handler (:app-ref ctx) action-event)))))
   :on-selection-change on-selection-change
   :on-text-change      (fn on-text-change [c ctx text-change-handler]
                          #_(doseq [dl (vec (-> c .getDocument .getDocumentListeners))]
                              (when (instance? DocumentListener dl) (.removeDocumentListener (.getDocument c) dl)))
                          (.addDocumentListener (.getDocument c)
                                                (reify-document-listener-to-text-change-listener
                                                  (fn text-change-handler-clojure [doc-event]
                                                    (text-change-handler (:app-ref ctx) (.getText c))))))
   :on-set-value-at     on-set-value-at
   ; TODO: refactor add-contents to a independent defn and check component type to be sure it's a valid container.
   ;  Perhaps pass in the map in addition to the component so that we don't have to use `instanceof`?
   ; TODO:
   ; - specify getter for existing child components
   ; - specify fn for adding new child component at specified index
   ; - no need to specify how to update a child component... that is just as if it was a root component.
   ; - no need to specify how to create a child component... that is also as if it was a root component.
   :contents            {:get-existing-children get-existing-children
                         :add-new-child-at      add-new-child-at
                         :remove-child-at       remove-child-at
                         :get-child-at          get-child-at}
   })
