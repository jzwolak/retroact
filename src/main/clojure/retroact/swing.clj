(ns retroact.swing
  (:require [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :as log]
            [clojure.set :refer [difference]]
            [retroact.swing.create-fns :as create]
            [retroact.swing.menu-bar :as mb]
            [retroact.swing.jlist :refer [create-jlist]]
            [retroact.swing.jtree :refer [create-jtree set-tree-model-fn set-tree-render-fn set-tree-data
                                          set-tree-toggle-click-count set-tree-selection-fn set-tree-scroll-path-fn]]
            [retroact.swing.jtable :refer [create-jtable safe-table-model-set]]
            [retroact.swing.jcombobox :refer [create-jcombobox]])
  (:import (clojure.lang ArityException Atom)
           (java.awt AWTEvent CardLayout Color Component Container Dimension BorderLayout EventQueue Font GridBagLayout Toolkit)
           (java.awt.dnd DnDConstants DragGestureListener DragSource DragSourceAdapter DropTarget DropTargetAdapter)
           (java.awt.event ActionListener ComponentAdapter ComponentListener MouseAdapter WindowAdapter)
           (java.beans PropertyChangeListener)
           (javax.swing JButton JCheckBox JComboBox JDialog JFileChooser JFrame JLabel JList JMenu JMenuItem JPanel JScrollPane JSeparator JSplitPane JTabbedPane JTextArea JTextField JComponent JTable JToggleButton JToolBar JTree RootPaneContainer SwingUtilities TransferHandler WindowConstants)
           (javax.swing.event ChangeListener DocumentListener ListSelectionListener TreeSelectionListener)
           (javax.swing.filechooser FileNameExtensionFilter)
           (net.miginfocom.swing MigLayout)
           (retroact.swing.compiled.retroact_invocation_event RetroactInvocationEvent)))


; TODO: I need to decide a regular way to pass arguments to handler fns. Currently I sometimes pass ctx, sometimes
; app-ref, sometimes the event, sometimes the data that is expected (like the text if it's a text change event), and
; almost always some combination of the above.
; I'm not sure the ctx is really necessary and it certainly would be outdated at the time the event actually executes.
; So perhaps app-ref, the event, and then optionally any data. The component that is the source of the event should
; always be obtainable by the event object, but if not, then I want to include that, too. The view should be on the
; component. Perhaps I can create a helper that quickly gets that - like (get-view (.getSource event)).
; Perhaps the rule of thumb should be to get things that are hard to get and not bother with things that are easy.
;
; Sometimes the event does not have a .getSource method. So pass the component too.
; Maybe use these as args:
;   app-ref component event optional-data
; Or maybe
;   ctx event optional-data
; where ctx contains app-ref, app-val, onscreen-component, and potentially old-view, new-view (or just view).
;
; I prefer the second: ctx, event, optional-data


(def client-prop-prefix "retroact-")

(defonce object-refs (atom {}))

; This holds a number for retroact initiated invocations on the EDT. Each invocation increments this value on start and
; decrements on finish. This allows other events enqueued on the event queue to be marked as initiated by Retroact.
; This is not foolproof as the event queue is thread safe and may take events from threads other than the EDT. Those
; events may - when enqueued during a retroact invocation - be mis-labelled as retroact initiated. The foolproof way
; to do this is to create a custom event queue that looks at where the events are coming from (what thread they're
; coming from) and only marks those events that are enqueued from the EDT during a retroact invocation as retroact
; initiated.
(def retroact-initiated (atom 0))

(defn get-scrollable-view
  "For views that are added to a JScrollPane by default, this will get the original view from the scroll pane if c
  is a scroll pane. Else, return c."
  [c]
  (if (instance? JScrollPane c)
    (.getView (.getViewport c))
    c))

(defn redraw-onscreen-component [c]
  (.revalidate c))

(defn- create-color [color]
  (cond
    (instance? Color color) color
    (and (vector? color) (= 3 (count color))) (Color. (nth color 0) (nth color 1) (nth color 2))
    (and (vector? color)) (Color. (nth color 0) (nth color 1) (nth color 2) (nth color 3))
    :else (Color. color)))

(defn- run-on-toolkit-thread-internal [f & args]
  (.postEvent ^EventQueue (-> (Toolkit/getDefaultToolkit) (.getSystemEventQueue))
              ^AWTEvent (RetroactInvocationEvent. (Toolkit/getDefaultToolkit)
                                                  (fn []
                                                    (apply f args)
                                                    (when (instance? Atom (first args))
                                                      #_(log/trace "got atom on toolkit thread with val:" @(first args)))
                                                    ))))

(defn run-on-toolkit-thread [f & args]
  (run-on-toolkit-thread-internal swap! retroact-initiated inc)
  (apply run-on-toolkit-thread-internal f args)
  ; Double invoke later on toolkit thread is necessary to capture secondary events caused by f.
  (run-on-toolkit-thread-internal #(SwingUtilities/invokeLater (fn [] (swap! retroact-initiated dec)
                                                                 #_(log/info "got atom on toolkit thread with val:" @retroact-initiated "after dec")))))


(defn retroact-initiated?
  "This only works sometimes in some cases. It needs to be refined. See comments for retroact-initiated var."
  []
  #_(let [instance-of (instance? RetroactInvocationEvent (EventQueue/getCurrentEvent))
        edt (SwingUtilities/isEventDispatchThread)
        atom-val (> @retroact-initiated 0)]
    (log/info "retroact-initiated? instance-of:" instance-of)
    (log/info "retroact-initiated? edt:" edt)
    (log/info "retroact-initiated? atom-val:" atom-val))
  (or
    (instance? RetroactInvocationEvent (EventQueue/getCurrentEvent))
    (and (SwingUtilities/isEventDispatchThread) (> @retroact-initiated 0))))

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

(defn reify-component-resize-listener
  [component-resize-handler]
  (proxy [ComponentAdapter] []
    (componentResized [component-event]
      (component-resize-handler component-event))))

(defn reify-component-hidden-listener
  [handler]
  (proxy [ComponentAdapter] []
    (componentHidden [component-event]
      (handler component-event))))

(defn reify-property-change-listener
  [property-change-handler]
  (reify PropertyChangeListener
    (propertyChange [this property-change-event]
      (property-change-handler property-change-event))))

(defn proxy-window-listener-close [app-ref onscreen-component handler]
  (proxy [WindowAdapter] []
    (windowClosing [window-event]
      (handler app-ref onscreen-component window-event))))

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
            ; TODO: I should also pass in the event to the selection-change-handler because selected values don't
            ; indicate the path and the same value may be at multiple leafs.
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

(defn- set-constraints
  "Attempt to update layout constraints for the component. This isn't possible for all layouts."
  [c ctx constraints]
  (try
    (let [layout-manager
          (-> (.getParent c)
              (.getLayout))]
      (condp instance? layout-manager
        BorderLayout (.addLayoutComponent layout-manager c constraints)
        GridBagLayout (.setConstraints layout-manager c constraints)
        MigLayout (.setComponentConstraints layout-manager c constraints)
        ))
    (catch Exception e
      ; ignore
      )))

(defn- set-title [c ctx title]
  (.setTitle c title))

(defn set-width [c ctx width]
  (let [view-width (get-in ctx [:old-view :width])
        onscreen-width (.getWidth c)
        height (.getHeight c)]
    (when (and (not= view-width onscreen-width) (not= onscreen-width width))
      (log/warn "onscreen width changed outside Retroact since last update. view-width =" view-width ", onscreen-width =" onscreen-width ", new-width =" width))
    (.setSize c (Dimension. width height))))

(defn set-height [c ctx height]
  (let [view-height (get-in ctx [:old-view :height])
        onscreen-height (.getHeight c)
        width (-> c .getSize .getWidth)]
    (when (and (not= view-height onscreen-height) (not= onscreen-height height))
      (log/warn "onscreen height changed outside Retroact since last update. view-height =" view-height ", onscreen-height =" onscreen-height ", new-height =" height))
    (.setSize c (Dimension. width height))))

(defn set-on-close [c ctx action]
  (if (contains? on-close-action-map action)
    (.setDefaultCloseOperation c (on-close-action-map action))
    (do
      (.setDefaultCloseOperation c WindowConstants/DO_NOTHING_ON_CLOSE)
      (.addWindowListener c (proxy-window-listener-close (:app-ref ctx) c action)))))


; :contents fns
(defmulti get-existing-children class)
(defmethod get-existing-children Container [c] (.getComponents c))
(defmethod get-existing-children RootPaneContainer [c] (.getComponents (.getContentPane c)))
(prefer-method get-existing-children RootPaneContainer Container)
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
(defmethod get-existing-children JMenu [menu]
  (.getMenuComponents menu))

(defmulti add-new-child-at (fn [container child _ _] (class container)))
(defmethod add-new-child-at Container [^Container c ^Component child view index]
  (.add ^Container c child (:constraints view) ^int index))
(defmethod add-new-child-at RootPaneContainer [^Container c ^Component child view index] (.add ^Container (.getContentPane c) child (:constraints view) ^int index))
(prefer-method add-new-child-at RootPaneContainer Container)
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
(defmethod add-new-child-at JMenu [menu child view index]
  (.add ^JMenu menu ^Component child ^int index))

(defmulti remove-child-at (fn [container index] (class container)))
(defmethod remove-child-at Container [c index] (.remove c index))
(defmethod remove-child-at RootPaneContainer [c index] (.remove (.getContentPane c) index))
(prefer-method remove-child-at RootPaneContainer Container)
(defmethod remove-child-at JList [jlist index] (println "JList remove-child-at not implemented yet"))
(defmethod remove-child-at JTabbedPane [tabbed-pane index]
  (log/info "removing tab, just want to check the current thread")
  (.removeTabAt tabbed-pane index))
(defmethod remove-child-at JMenu [menu index]
  (.remove menu ^int index))

(defmulti get-child-at (fn [container index] (class container)))
(defmethod get-child-at Container [c index] (.getComponent c index))
(defmethod get-child-at RootPaneContainer [c index] (.getComponent (.getContentPane c) index))
(prefer-method get-child-at RootPaneContainer Container)
(defmethod get-child-at JList [jlist index]
  (let [child
        (-> jlist
            (.getModel)
            (.getElementAt index))]
    (println "jlist get-child-at:" child)
    child))
(defmethod get-child-at JTabbedPane [tabbed-pane index] (.getComponentAt tabbed-pane index))
(defmethod get-child-at JMenu [menu index] (.getMenuComponent menu index))
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
  {:default                    (fn default-swing-component-constructor [ui]
                    (log/warn "using default constructor to generate a JPanel")
                    (JPanel.))
   :border-layout              BorderLayout
   :button                     JButton
   :card-layout                CardLayout
   :check-box                  JCheckBox
   :combo-box                  create-jcombobox
   :dialog                     create/create-jdialog                            ; uses :owner attr in constructor
   :file-chooser               JFileChooser
   :file-name-extension-filter create/create-file-name-extension-filter
   :frame                      JFrame
   :label                      JLabel
   :list                       create-jlist
   :menu                       JMenu
   :menu-item                  JMenuItem
   :mig-layout                 MigLayout
   :panel                      JPanel
   :separator                  JSeparator
   :split-pane                 JSplitPane
   :tabbed-pane                JTabbedPane
   :table                      create-jtable
   :text-area                  JTextArea
   :text-field                 JTextField
   :toggle-button              JToggleButton
   :tool-bar                   JToolBar
   :tree                       create-jtree})

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

(defn- set-on-tab
  [c set-fn]
  (let [parent (.getParent c)
        [tabbed-pane child] (if (instance? JScrollPane parent)
                              [(.getParent parent) parent]
                              [parent c])]
    (if (instance? JTabbedPane tabbed-pane)
      (let [index (.indexOfComponent tabbed-pane child)]
        (set-fn tabbed-pane index))
      (log/warn "could not set title for tab because component is not a child of a tabbed pane"))))

(defn- set-tab-title
  [c ctx title]
  (set-on-tab c (fn [tabbed-pane index] (.setTitleAt tabbed-pane index title))))

(defn- set-tab-tooltip
  [c ctx tooltip]
  (set-on-tab c (fn [tabbed-pane index] (.setToolTipTextAt tabbed-pane index tooltip))))

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
    (set-row-selection-interval (get-scrollable-view c) ctx [start end])
    (if (instance? JTable c)
      (SwingUtilities/invokeLater
        #(do
           (if (and start end)
            (.setRowSelectionInterval c start end)
            (.clearSelection c)))))))

(defn on-change [c ctx change-handler]
  (.addChangeListener c (reify-change-listener (fn [ce] (change-handler ctx ce)))))

(defn on-component-resize [c ctx component-resize-handler]
  (.addComponentListener c (reify-component-resize-listener (fn [ce] (component-resize-handler ctx ce)))))

(defn on-component-hidden [c ctx handler]
  (.addComponentListener c (reify-component-hidden-listener (fn [ce] (handler ctx ce)))))

(defn- on-drag
  "When handler returns a truthy value, the value is treated as the transferable and DragSource.startDrag is called."
  [c {:keys [app-ref] :as ctx} handler]
  (let [c (get-scrollable-view c)
        drag-source (DragSource.)
        local-ctx (assoc ctx :onscreen-component c)]
    ;(swap! object-refs assoc :drag-source drag-source)
    (log/info "connecting on-drag handler")
    (log/info "object-refs =" @object-refs)
    (log/info "component for drag source:" c)
    ;TODO: allow the user to decide what DnD action to use. Defaulting to ACTION_COPY now.
    (.createDefaultDragGestureRecognizer
      drag-source c DnDConstants/ACTION_COPY
      (reify DragGestureListener
        (dragGestureRecognized [this drag-gesture-event]
          (log/info "retroact recognizing drag gesture")
          (when-let [transferable (handler (assoc local-ctx :app-val @app-ref) drag-gesture-event)]
            (.startDrag drag-gesture-event DragSource/DefaultCopyDrop transferable (proxy [DragSourceAdapter] []))))))))

(defn- on-drag-over [c ctx handler])

(defn- on-drop [c {:keys [app-ref] :as ctx} handler]
  (let [local-ctx (assoc ctx :onscreen-component c)
        drop-target (DropTarget. c DnDConstants/ACTION_COPY
                                 (proxy [DropTargetAdapter] []
                                   (drop [drop-event] (handler (assoc local-ctx :app-val @app-ref) drop-event))))]
    (log/info "drop target created... but will it be retained? Or garbage collected")))

(defn- set-drag-enabled [c ctx drag-enabled]
  (.setDragEnabled (get-scrollable-view c) drag-enabled))

(defn- set-transfer-handler [c ctx transfer-handler]
  (.setTransferHandler (get-scrollable-view c) transfer-handler))

(defn on-property-change [c ctx property-change-handler]
  (.addPropertyChangeListener c (reify-property-change-listener (fn [pce] (property-change-handler ctx pce)))))

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
  (on-selection-change (get-scrollable-view c) ctx selection-change-handler))

(defn on-text-change [c ctx text-change-handler]
  #_(doseq [dl (vec (-> c .getDocument .getDocumentListeners))]
      (when (instance? DocumentListener dl) (.removeDocumentListener (.getDocument c) dl)))
  (.addDocumentListener (.getDocument c)
                        (reify-document-listener-to-text-change-listener
                          (fn text-change-handler-clojure [doc-event]
                            (try
                              (text-change-handler (:app-ref ctx) c doc-event (.getText c))
                              (catch ArityException ae
                                (log/warn "two arg version of :on-text-change handler deprecated, please update to"
                                          "four args with the component and doc-event as the second and third args")
                                (text-change-handler (:app-ref ctx) (.getText c))))))))

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
    (on-click (get-scrollable-view c) ctx click-handler)))


; *** :render and :class are reserved attributes, do not use! ***
(def attr-appliers
  {:background             (fn set-background [c ctx color] (cond
                                                              (instance? RootPaneContainer c) (.setBackground (.getContentPane c) (create-color color))
                                                              :else (.setBackground c (create-color color))))
   :border                 (fn set-border [c ctx border] (.setBorder c border))
   :client-properties      update-client-properties
   :color                  (fn set-color [c ctx color ] (cond
                                                         (instance? RootPaneContainer c) (.setForeground (.getContentPane c) (create-color color))
                                                         :else (.setForeground c (create-color color))))
   :constraints            set-constraints
   :content-area-filled    (fn set-content-area-filled [c ctx filled] (.setContentAreaFilled c filled))
   :description            {:recreate [FileNameExtensionFilter]}
   :dialog-type            (fn set-dialog-type [c ctx dialog-type] (.setDialogType c dialog-type))
   :enabled                (fn set-enabled [c ctx enabled]
                             (log/info ".setEnabled to" (boolean enabled) "with raw val" enabled "on" c)
                             (.setEnabled c ^boolean (boolean enabled)))
   :extensions             {:recreate [FileNameExtensionFilter]}
   :file-filter            {:set (fn set-file-filter [c ctx file-filter] (.setFileFilter c file-filter))
                            :get (fn get-file-filter [c ctx] (.getFileFilter c))}
   :font-size              (fn set-font-size [c ctx size] (let [f (.getFont c)] (.setFont c (.deriveFont ^Font f ^int (.getStyle f) ^float size))))
   :font-style             (fn set-font-style [c ctx style] (let [f (.getFont c)] (.setFont c (.deriveFont ^Font f ^int style ^float (.getSize f)))))
   :icon                   (fn set-icon [c ctx icon] (.setIcon c icon))
   :data                   (fn set-retroact-data [c ctx data] (set-client-prop c "data" data))
   ; The following doesn't appear to work. It may be that macOS overrides margins. Try using empty border.
   :margin                 (fn set-margin [c ctx insets] (.setMargin c insets))
   :modal                  (fn set-modal [c ctx modal] (.setModal c modal))
   :height                 set-height
   :layout                 {:set (fn set-layout [c ctx layout] (.setLayout c layout))
                            :get (fn get-layout [c ctx]
                                   (cond
                                     (instance? RootPaneContainer c) (.getLayout (.getContentPane c))
                                     :else (.getLayout c)))}
   :line-wrap              (fn set-line-wrap [c ctx line-wrap] (.setLineWrap c line-wrap))
   :name                   (fn set-name [c ctx name] (.setName c name))
   :opaque                 (fn set-opaque [c ctx opaque] (cond
                                                           (instance? RootPaneContainer c) (.setOpaque (.getContentPane c) opaque)
                                                           :else (.setOpaque c opaque)))
   :owner                  {:recreate [JDialog]}
   :row-selection-interval set-row-selection-interval
   :selected               (fn set-selected [c ctx selected?]
                             (.setSelected c (boolean selected?)))
   :selected-index         {:deps [:contents]
                            :fn   (fn set-selected-index [c ctx index] (.setSelectedIndex c index))}
   :selection-mode         (fn set-selection-mode [c ctx selection-mode] (.setSelectionMode ^JList c selection-mode))
   :text                   (fn set-text [c ctx text]
                             #_(.printStackTrace (Exception. "stack trace"))
                             (let [old-text (.getText c)
                                   new-text (str text)]
                               ; TODO: should be unnecessary. This must be left over from the early days.
                               ; Though maybe it has to do with nil and empty string being treated the same. See text-changed?
                               ; I added (str text) though, which converts nil to the empty string, so text-changed?
                               ; shouldn't be necessary, I think.
                               (when (text-changed? old-text new-text)
                                 (.setText c new-text))))
   :title                  set-title
   :visible                (fn set-visible [c ctx visible] (.setVisible c (boolean visible)))
   :width                  set-width
   :caret-position         (fn set-caret-position [c ctx position] (.setCaretPosition c position))
   :on-close               set-on-close
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
   :divider-location       (fn set-divider-location [c ctx location]
                             (log/warn "setting divider location to" location)
                             (if (int? location)
                               (.setDividerLocation c ^int (int location))
                               (do
                                 (log/warn "using divider ratio")
                                 (.setDividerLocation c ^double (double location)))))
   ; careful with this one, it only works _after_ the component has a size... that is, rendered on screen.
   ;   :divider-location-ratio
   #_(fn set-divider-location [c ctx location]
       (log/warn "setting divider location to ratio" location)
       (.setDividerLocation c ^double location))
   ; Tabbed Pane attr appliers
   :tab-title              set-tab-title
   :tab-tooltip            set-tab-tooltip
   ; Table attr appliers
   :column-names           set-column-names
   :row-editable-fn        set-row-editable-fn
   :row-fn                 set-row-fn
   :table-data             set-table-data
   ; Tree attr appliers
   :tree-render-fn         set-tree-render-fn
   :tree-model-fn          set-tree-model-fn
   :tree-selection-fn      set-tree-selection-fn
   :tree-scroll-path-fn    set-tree-scroll-path-fn
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
   :on-component-resize    on-component-resize
   :on-component-hidden    on-component-hidden
   :on-property-change     on-property-change
   :on-selection-change    on-selection-change
   :on-text-change         on-text-change
   :on-set-value-at        on-set-value-at
   ; Mouse listeners
   :on-click               on-click
   ; Menus
   :menu-bar               {:get-existing-children mb/get-existing-children
                            :add-new-child-at      mb/add-new-child-at
                            :remove-child-at       mb/remove-child-at
                            :get-child-at          mb/get-child-at}
   ; Drag and Drop
   ; :on-drag, :on-drag-over, and :on-drop may be used together to implement drag and drop, however, another way exists.
   ; See :drag-enabled
   :on-drag                on-drag
   :on-drag-over           on-drag-over
   :on-drop                on-drop
   ; In some cases, this is the _only_ thing necessary to enable drag and drop. Also setting the :transfer-handler will
   ; expand the abilities of this approach.
   :drag-enabled           set-drag-enabled
   :transfer-handler       set-transfer-handler
   ; TODO: refactor add-contents to a independent defn and check component type to be sure it's a valid container.
   ;  Perhaps pass in the map in addition to the component so that we don't have to use `instanceof`?
   ; TODO:
   ; - specify getter for existing child components
   ; - specify fn for adding new child component at specified index
   ; - no need to specify how to update a child component... that is just as if it was a root component.
   ; - no need to specify how to create a child component... that is also as if it was a root component.
   :contents               {:deps                  [:layout]
                            :get-existing-children get-existing-children
                            :add-new-child-at      add-new-child-at
                            :remove-child-at       remove-child-at
                            :get-child-at          get-child-at}
   })

(def toolkit-config
  {:attr-appliers             `attr-appliers
   :assoc-view                `assoc-view
   :get-view                  `get-view
   :redraw-onscreen-component `redraw-onscreen-component
   :class-map                 `class-map
   :run-on-toolkit-thread     `run-on-toolkit-thread})
