(ns retroact.swing
  (:require [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :as log]
            [clojure.set :refer [difference]]
            [retroact.swing.jlist :refer [create-jlist]]
            [retroact.swing.jtree :refer [create-jtree set-tree-model-fn set-tree-render-fn set-tree-data
                                          set-tree-toggle-click-count]]
            [retroact.swing.jtable :refer [create-jtable safe-table-model-set]]
            [retroact.swing.jcombobox :refer [create-jcombobox]])
  (:import (java.awt CardLayout Color Component Container Dimension BorderLayout)
           (java.awt.event ActionListener MouseAdapter)
           (javax.swing JButton JCheckBox JComboBox JFrame JLabel JList JPanel JScrollPane JSplitPane JTabbedPane JTextField JComponent JTable JToolBar JTree SwingUtilities)
           (javax.swing.event ChangeListener DocumentListener ListSelectionListener TreeSelectionListener)
           (net.miginfocom.swing MigLayout)))


(def client-prop-prefix "retroact-")

(defn redraw-onscreen-component [c]
  (.revalidate c))

(defn run-on-toolkit-thread [f & args]
  (SwingUtilities/invokeLater #(apply f args)))

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

(def orientation-map
  {:vertical JSplitPane/VERTICAL_SPLIT
   :horizontal JSplitPane/HORIZONTAL_SPLIT})

(defn reify-action-listener
  [action-handler]
  (reify ActionListener
    (actionPerformed [this action-event]
      (action-handler action-event))))

(defn reify-change-listener
  [change-handler]
  (reify ChangeListener
    (stateChanged [this change-event]
      (change-handler change-event))))

(defn proxy-mouse-listener-click [app-ref click-handler]
  (proxy [MouseAdapter] []
    (mousePressed [mouse-event]
      (click-handler app-ref mouse-event))
    (mouseClicked [mouse-event]
      (click-handler app-ref mouse-event))))

(defn reify-tree-selection-listener [ctx selection-change-handler]
  (reify TreeSelectionListener
    (valueChanged [this event]
      (let [onscreen-component (.getSource event)
            selected-values (mapv (fn [tree-path] (.getLastPathComponent tree-path)) (.getSelectionPaths onscreen-component))]
        (selection-change-handler (:app-ref ctx) (get-view onscreen-component) onscreen-component selected-values)))))

(defn reify-list-selection-listener [ctx selection-change-handler]
  (reify ListSelectionListener
    (valueChanged [this event]
      (let [onscreen-component (.getSource event)
            selected-values (mapv get-view-or-identity (.getSelectedValuesList onscreen-component))]
        (when (not (.getValueIsAdjusting event))
          (selection-change-handler (:app-ref ctx) (get-view onscreen-component) onscreen-component selected-values))))))

(defn reify-tree-list-selection-listener [ctx table selection-change-handler]
  (reify ListSelectionListener
    (valueChanged [this event]
      (let [event-source (.getSource event)
            table-model (.getModel table)
            selected-indices (seq (.getSelectedIndices event-source))
            selected-values (mapv (fn get-item [i] (.getItemAt table-model i)) selected-indices)]
        (when (not (.getValueIsAdjusting event))
          (selection-change-handler (:app-ref ctx) (get-view table) table selected-values))))))

(defn reify-document-listener-to-text-change-listener [text-change-handler]
  (reify DocumentListener
    (changedUpdate [this document-event] (text-change-handler document-event))
    (insertUpdate [this document-event] (text-change-handler document-event))
    (removeUpdate [this document-event] (text-change-handler document-event))))

(defn update-client-properties [c ctx properties]
  (let [{:keys [old-view attr]} ctx
        old-properties (get old-view attr)
        old-keys (set (keys (into {} old-properties)))
        new-keys (set (keys (into {} properties)))
        missing-keys (difference old-keys new-keys)]
    (doseq [k missing-keys] (.putClientProperty c k nil))
    (doseq [[k v] properties] (.putClientProperty c k v))))

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
    (log/warn "JList get-existing-children not implemented")
    (-> model
        (.elements)
        (enumeration-seq)
        (vec))))
(defmethod get-existing-children JTabbedPane [tabbed-pane]
  (let [num-tabs (.getTabCount tabbed-pane)]
    (mapv #(.getTabComponentAt tabbed-pane %) (range 0 num-tabs))))

(defmulti add-new-child-at (fn [container child _ _] (class container)))
(defmethod add-new-child-at Container [^Container c ^Component child view index]
  (.add ^Container c child (:constraints view) ^int index))
(defmethod add-new-child-at JFrame [^Container c ^Component child view index] (.add ^Container (.getContentPane c) child (:constraints view) ^int index))
(defmethod add-new-child-at JList [^JList jlist ^Component child view index]
  (let [model (.getModel jlist)]
    (println "jlist add-new-child-at" index "(model class:" (class model) " size =" (.getSize model) ")" child)
    (.add model index child)
    child))
(defmethod add-new-child-at JTabbedPane [tabbed-pane child view index]
  (let [title (or (:tab-title view) "New Tab")
        icon (:tab-icon view)
        tooltip (:tab-tooltip view)]
    (.insertTab tabbed-pane title icon child tooltip index)))

(defmulti remove-child-at (fn [container index] (class container)))
(defmethod remove-child-at Container [c index] (.remove c index))
(defmethod remove-child-at JFrame [c index] (.remove (.getContentPane c) index))
(defmethod remove-child-at JList [jlist index] (println "JList remove-child-at not implemented yet"))
(defmethod remove-child-at JTabbedPane [tabbed-pane index] (.removeTabAt tabbed-pane index))

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
(defmethod get-child-at JTabbedPane [tabbed-pane index] (.getComponentAt tabbed-pane index))
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
   :card-layout   CardLayout
   :check-box     JCheckBox
   :combo-box     create-jcombobox
   :frame         JFrame
   :label         JLabel
   :list          create-jlist
   :mig-layout    MigLayout
   :panel         JPanel
   :split-pane    JSplitPane
   :tabbed-pane   JTabbedPane
   :table         create-jtable
   :text-field    JTextField
   :tool-bar      JToolBar
   :tree          create-jtree})

(defn text-changed? [old-text new-text]
  (not
    (or (= old-text new-text)
        ; empty string and nil are treated the same. See JTextComponent.setText().
        (and (nil? old-text) (empty? new-text))
        (and (empty? old-text) (nil? new-text)))))

(defn show-card
  "Shows card of container c when CardLayout is being used."
  [c ctx card-name]
  (.show (.getLayout c) c card-name))

(defn set-combo-box-data
  [c ctx data]
  (let [model (.getModel c)]
    (.removeAllElements model)
    (.addAll model data)))

(defn set-tab-title
  [c ctx title]
  (let [parent (.getParent c)
        [tabbed-pane child] (if (instance? JScrollPane parent)
                              [(.getParent parent) parent]
                              [parent c])]
    (if (instance? JTabbedPane tabbed-pane)
      (let [index (.indexOfComponent tabbed-pane child)]
        (.setTitleAt tabbed-pane index title))
      (log/warn "could not set title for tab because component is not a child of a tabbed pane"))))

(defn set-column-names
  [c ctx column-names]
  (safe-table-model-set c (memfn setColumnNames column-names) column-names))

(defn set-row-fn
  "Set the row fn on the table model of a JTable. The row fn takes the nth element in the data vector and returns a
  vector representing the columns in that row."
  [c ctx row-fn]
  (safe-table-model-set c (memfn setRowFn row-fn) row-fn))

(defn set-table-data
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

(defn set-row-selection-interval [c ctx [start end]]
  (if (instance? JScrollPane c)
    (set-row-selection-interval (.getView (.getViewport c)) ctx [start end])
    (if (instance? JTable c)
      (SwingUtilities/invokeLater
        #(do
           (if (and start end)
            (.setRowSelectionInterval c start end)
            (.clearSelection c)))))))

(defn on-change [c ctx change-handler]
  (.addChangeListener c (reify-change-listener (fn [ce] (change-handler ctx ce)))))

(defmulti on-selection-change (fn [c _ _] (class c)))

(defmethod on-selection-change JComboBox [c ctx selection-change-handler]
  (doseq [al (vec (.getActionListeners c))] (.removeActionListener c al))
  (.addActionListener c (reify-action-listener
                          (fn [ae]
                            (let [selected-item (.getSelectedItem (.getModel c))]
                              (selection-change-handler ctx ae selected-item))))))

(defmethod on-selection-change JTree [c ctx selection-change-handler]
  (doseq [sl (vec (.getTreeSelectionListeners c))] (.removeTreeSelectionListener c sl))
  (.addTreeSelectionListener c (reify-tree-selection-listener ctx selection-change-handler)))

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

(defn on-click
  [c ctx click-handler]
  (if (not (instance? JScrollPane c))
    (.addMouseListener
      c (proxy-mouse-listener-click (:app-ref ctx) click-handler))
    (let [child (.getView (.getViewport c))]
      (on-click child ctx click-handler))))


; *** :render and :class are reserved attributes, do not use! ***
(def attr-appliers
  {:background             (fn set-background [c ctx color] (cond
                                                           (instance? JFrame c) (.setBackground (.getContentPane c) (Color. color))
                                                           :else (.setBackground c (Color. color))))
   :border                 (fn set-border [c ctx border] (.setBorder c border))
   :client-properties      update-client-properties
   :content-area-filled    (fn set-content-area-filled [c ctx filled] (.setContentAreaFilled c filled))
   :enabled                (fn set-enabled [c ctx enabled] (.setEnabled c enabled))
   :icon                   (fn set-icon [c ctx icon] (.setIcon c icon))
   :data                   (fn set-retroact-data [c ctx data] (set-client-prop c "data" data))
   ; The following doesn't appear to work. It may be that macOS overrides margins. Try using empty border.
   :margin                 (fn set-margin [c ctx insets] (.setMargin c insets))
   :height                 set-height
   :layout                 {:set (fn set-layout [c ctx layout] (.setLayout c layout))
                            :get (fn get-layout [c ctx]
                                   (cond
                                     (instance? JFrame c) (.getLayout (.getContentPane c))
                                     :else (.getLayout c)))}
   :name                   (fn set-name [c ctx name] (.setName c name))
   :opaque                 (fn set-opaque [c ctx opaque] (cond
                                                           (instance? JFrame c) (.setOpaque (.getContentPane c) opaque)
                                                           :else (.setOpaque c opaque)))
   :row-selection-interval set-row-selection-interval
   :selected               (fn set-selected [c ctx selected?]
                             (.setSelected c selected?))
   :selected-index         {:deps [:contents]
                            :fn (fn set-selected-index [c ctx index] (.setSelectedIndex c index))}
   :selection-mode         (fn set-selection-mode [c ctx selection-mode] (.setSelectionMode ^JList c selection-mode))
   :text                   (fn set-text [c ctx text]
                             #_(.printStackTrace (Exception. "stack trace"))
                             (let [old-text (.getText c)]
                               (when (text-changed? old-text text)
                                 (.setText c text))))
   :width                  set-width
   :caret-position         (fn set-caret-position [c ctx position] (.setCaretPosition c position))
   ; TODO: if action not in on-close-action-map, then add it as a WindowListener to the close event
   :on-close               (fn on-close [c ctx action]
                             (.setDefaultCloseOperation c (on-close-action-map action)))
   :layout-constraints     (fn set-layout-constraints [c ctx constraints] (.setLayoutConstraints c constraints))
   :row-constraints        (fn set-row-constraints [c ctx constraints] (.setRowConstraints c constraints))
   :column-constraints     (fn set-column-constraints [c ctx constraints]
                             (.setColumnConstraints c constraints))
   ; Card Layout
   :show-card              {:deps [:layout :contents]
                            :fn   show-card}
   ; Combo Box attr appliers
   :combo-box-data         set-combo-box-data
   :selected-item          (fn set-selected-item [c ctx item] (.setSelectedItem c item))
   ; Split Pane attr appliers
   :left-component         {:set (fn set-left-comp [c ctx comp] (.setLeftComponent c comp))
                            :get (fn get-left-comp [c ctx] (.getLeftComponent c))}
   :right-component        {:set (fn set-right-comp [c ctx comp] (.setRightComponent c comp))
                            :get (fn get-right-comp [c ctx] (.getRightComponent c))}
   :top-component          {:set (fn set-top-comp [c ctx comp] (.setTopComponent c comp))
                            :get (fn get-top-comp [c ctx] (.getTopComponent c))}
   :bottom-component       {:set (fn set-bottom-comp [c ctx comp] (.setBottomComponent c comp))
                            :get (fn get-bottom-comp [c ctx] (.getBottomComponent c))}
   :one-touch-expandable   (fn set-one-touch-expandable [c ctx expandable] (.setOneTouchExpandable c expandable))
   :orientation            (fn set-orientation [c ctx orientation] (.setOrientation c (orientation-map orientation)))
   :divider-location       (fn set-divider-location [c ctx location] (.setDividerLocation c ^Integer location))
   ; Tabbed Pane attr appliers
   :tab-title              set-tab-title
   ; Table attr appliers
   :column-names           set-column-names
   :row-editable-fn        set-row-editable-fn
   :row-fn                 set-row-fn
   :table-data             set-table-data
   ; Tree attr appliers
   :tree-render-fn         set-tree-render-fn
   :tree-model-fn          set-tree-model-fn
   :tree-data              set-tree-data
   :toggle-click-count     set-tree-toggle-click-count

   ; All action listeners must be removed before adding the new one to avoid re-adding the same anonymous fn.
   :on-action              (fn on-action [c ctx action-handler]
                             #_(if (instance? JCheckBox c) (action-handler (:app-ref ctx) nil))
                             (doseq [al (vec (.getActionListeners c))]
                               (.removeActionListener c al))
                             (.addActionListener c (reify-action-listener (fn action-handler-clojure [action-event]
                                                                            (action-handler (:app-ref ctx) action-event)))))
   :on-change              on-change
   :on-selection-change    on-selection-change
   :on-text-change         (fn on-text-change [c ctx text-change-handler]
                             #_(doseq [dl (vec (-> c .getDocument .getDocumentListeners))]
                                 (when (instance? DocumentListener dl) (.removeDocumentListener (.getDocument c) dl)))
                             (.addDocumentListener (.getDocument c)
                                                   (reify-document-listener-to-text-change-listener
                                                     (fn text-change-handler-clojure [doc-event]
                                                       (text-change-handler (:app-ref ctx) (.getText c))))))
   :on-set-value-at        on-set-value-at
   ; Mouse listeners
   :on-click               on-click
   ; TODO: refactor add-contents to a independent defn and check component type to be sure it's a valid container.
   ;  Perhaps pass in the map in addition to the component so that we don't have to use `instanceof`?
   ; TODO:
   ; - specify getter for existing child components
   ; - specify fn for adding new child component at specified index
   ; - no need to specify how to update a child component... that is just as if it was a root component.
   ; - no need to specify how to create a child component... that is also as if it was a root component.
   :contents               {:deps [:layout]
                            :get-existing-children get-existing-children
                            :add-new-child-at      add-new-child-at
                            :remove-child-at       remove-child-at
                            :get-child-at          get-child-at}
   })

(def toolkit-config
  {:attr-appliers             `attr-appliers
   :assoc-view                `assoc-view
   :redraw-onscreen-component `redraw-onscreen-component
   :class-map                 `class-map
   :run-on-toolkit-thread     `run-on-toolkit-thread})
