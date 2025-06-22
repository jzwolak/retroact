(ns retroact.swing
  (:require [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.set :refer [difference]]
            [retroact.swing.create-fns :as create]
            [retroact.swing.borders :as borders]
            [retroact.swing.event-queue :as event-queue]
            [retroact.swing.listeners :as listeners]
            [retroact.swing.menu-bar :as mb]
            [retroact.swing.jlist :refer [create-jlist]]
            [retroact.swing.jtree :refer [create-jtree set-tree-model-fn set-tree-render-fn set-tree-data
                                          set-tree-toggle-click-count set-tree-selection-fn set-tree-scroll-path-fn]]
            [retroact.swing.jtable :refer [create-jtable safe-table-model-set]]
            [retroact.swing.jcombobox :refer [create-jcombobox]]
            [retroact.swing.util :as util :refer [silenced-events]]
            [retroact.toolkit.property-getters-setters :refer [set-property]])
  (:import (clojure.lang ArityException Atom)
           (java.awt AWTEvent CardLayout Color Component Container Dimension BorderLayout EventQueue Font GridBagLayout Toolkit Window)
           (java.awt.dnd DnDConstants DragGestureListener DragSource DragSourceAdapter DropTarget DropTargetAdapter)
           (java.awt.event ActionListener ComponentAdapter ComponentListener FocusAdapter FocusListener KeyListener MouseAdapter MouseListener MouseWheelListener WindowAdapter)
           (java.beans PropertyChangeListener)
           (java.util WeakHashMap)
           (javax.swing JButton JCheckBox JCheckBoxMenuItem JComboBox JDialog JFileChooser JFrame JLabel JList JMenu JMenuItem JPanel JPopupMenu JProgressBar JScrollPane JSeparator JSplitPane JTabbedPane JTextArea JTextField JComponent JTable JTextPane JToggleButton JToolBar JTree RootPaneContainer SwingConstants SwingUtilities TransferHandler WindowConstants)
           (javax.swing.border TitledBorder)
           (javax.swing.event ChangeListener DocumentListener ListSelectionListener TreeSelectionListener)
           (javax.swing.filechooser FileNameExtensionFilter)
           (net.miginfocom.swing MigLayout)
           (retroact.swing.compiled.identity_wrapper IdentityWrapper)
           (retroact.swing.compiled.listeners RetroactSwingListener RetroactSwingOnAction RetroactSwingOnChange RetroactSwingOnClick RetroactSwingOnComponentHidden RetroactSwingOnComponentResize RetroactSwingOnComponentShown RetroactSwingOnFocusGained RetroactSwingOnFocusLost RetroactSwingOnKeyPressed RetroactSwingOnMouseWheelMoved RetroactSwingOnPropertyChange RetroactSwingOnSelectionChange RetroactSwingOnTextChange)
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
; I prefer the second: ctx, event, optional-data. Perhaps even put optional-data in the ctx as :data or whatever. Doing
; so would create a uniform 2-arg handler interface. And perhaps I should switch the order of ctx and event.
;
; Ok, for now, go with ctx and event as only two args. Put :data in ctx along with whatever else I can.
;


#_(defonce comp->props (atom {:object->identity (WeakHashMap.)
                            :identity->props (WeakHashMap.)}))

; This holds a number for retroact initiated invocations on the EDT. Each invocation increments this value on start and
; decrements on finish. This allows other events enqueued on the event queue to be marked as initiated by Retroact.
; This is not foolproof as the event queue is thread safe and may take events from threads other than the EDT. Those
; events may - when enqueued during a retroact invocation - be mis-labelled as retroact initiated. The foolproof way
; to do this is to create a custom event queue that looks at where the events are coming from (what thread they're
; coming from) and only marks those events that are enqueued from the EDT during a retroact invocation as retroact
; initiated.
(def retroact-initiated (atom 0))

(defn create-handler-context [ctx onscreen-component]
  ; ctx from onscreen-component has :old-view, :new-view, and :view, where :view is equal to either :old-view or
  ; :new-view depending on if an update is running or finished, respectively.
  ; Except that all those views may be wrong because ctx is captured when the handler is first assigned. If the handler
  ; doesn't change then the ctx won't be updated. So I'm setting :view. I hope it doesn't break anything.
  (assoc (merge ctx (util/get-ctx onscreen-component))
    :view (util/get-view onscreen-component)
    :onscreen-component onscreen-component
    :app-val @(:app-ref ctx)))

(defn get-scrollable-view
  "For views that are added to a JScrollPane by default, this will get the original view from the scroll pane if c
  is a scroll pane. Else, return c."
  [c]
  (if (instance? JScrollPane c)
    (.getView (.getViewport c))
    c))

(defn- set-property-on-root-pane [c new-value getter setter]
  (let [c (if (instance? RootPaneContainer c) (.getContentPane c) c)]
    (set-property c new-value getter setter)))

(defn redraw-onscreen-component [c]
  (.revalidate c)
  (.repaint c))

(defn- run-on-toolkit-thread-internal [f & args]
  #_@event-queue/event-queue-register
  (.postEvent ^EventQueue (-> (Toolkit/getDefaultToolkit) (.getSystemEventQueue))
              ^AWTEvent (RetroactInvocationEvent. (Toolkit/getDefaultToolkit)
                                                  (fn toolkit-thread-runnable-fn []
                                                    (apply f args)
                                                    #_(when (instance? Atom (first args))
                                                      (log/trace "got atom on toolkit thread with val:" @(first args)))
                                                    ))))

(defn run-on-toolkit-thread [f & args]
  (if (SwingUtilities/isEventDispatchThread)
    (do
      (swap! retroact-initiated inc)
      (try (apply f args)
           (finally
             (SwingUtilities/invokeLater
               (fn dec-retroact-initiated [] (swap! retroact-initiated dec))))))
    (do
      (run-on-toolkit-thread-internal swap! retroact-initiated inc)
      (apply run-on-toolkit-thread-internal f args)
      ; Double invoke later on toolkit thread is necessary to capture secondary events caused by f.
      (run-on-toolkit-thread-internal (fn outer-dec-retroact-initiated []
                                        (SwingUtilities/invokeLater
                                          (fn dec-retroact-initiated [] (swap! retroact-initiated dec))))))))


(defn retroact-initiated?
  "This only works sometimes in some cases. It needs to be refined. See comments for retroact-initiated var.
  Update: maybe this was refined. The RetroactInvocationEvent definitely improved this."
  []
  #_(let [instance-of (instance? RetroactInvocationEvent (EventQueue/getCurrentEvent))
        edt (SwingUtilities/isEventDispatchThread)
        atom-val (> @retroact-initiated 0)]
    (log/info "retroact-initiated? instance-of:" instance-of)
    (log/info "retroact-initiated? edt:" edt)
    (log/info "retroact-initiated? atom-val:" atom-val))
  (or
    (instance? RetroactInvocationEvent (EventQueue/getCurrentEvent))
    (and (SwingUtilities/isEventDispatchThread) (> @retroact-initiated 0)))
  #_(event-queue/retroact-initiated?))

(def on-close-action-map
  {:dispose JFrame/DISPOSE_ON_CLOSE
   :do-nothing JFrame/DO_NOTHING_ON_CLOSE
   :exit JFrame/EXIT_ON_CLOSE
   :hide JFrame/HIDE_ON_CLOSE})

(def pane-orientation-map
  {:vertical JSplitPane/VERTICAL_SPLIT
   :horizontal JSplitPane/HORIZONTAL_SPLIT})

(def orientation-map
  {:vertical   SwingConstants/VERTICAL
   :horizontal SwingConstants/HORIZONTAL})

(defn- set-accelerator [c ctx key-stroke]
  (.setAccelerator c key-stroke))

(defn- set-background [c ctx color]
  (set-property-on-root-pane c (create/create-color color) #(.getBackground %) #(.setBackground %1 %2)))

(defn set-foreground [c ctx color]
  (set-property-on-root-pane c (create/create-color color) #(.getForeground %) #(.setForeground %1 %2)))

(defn set-columns [c ctx num-columns]
  (.setColumns c num-columns))

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

(defn set-editable [c ctx editable]
  (set-property-on-root-pane c editable #(.isEditable %) #(.setEditable %1 %2)))

(defn- set-enabled [c ctx enabled]
  (set-property-on-root-pane c enabled #(.isEnabled %) (fn [c e] (.setEnabled c ^boolean (boolean e)))))

(defn- set-title [c ctx title]
  (.setTitle c title))

(defn- set-tool-tip-text [c ctx tool-tip-text]
  (.setToolTipText c tool-tip-text))

(defn- set-scroll-bar [scroll-bar position]
  (.setValue scroll-bar position))

(defn- set-vertical-scroll-bar [c ctx position]
  (let [scroll-bar (.getVerticalScrollBar c)]
    (set-scroll-bar scroll-bar position)))

(defn- set-horizontal-scroll-bar [c ctx position]
  (let [scroll-bar (.getHorizontalScrollBar c)]
    (set-scroll-bar scroll-bar position)))

(defn- set-horizontal-scroll-bar-policy [c ctx policy]
  (.setHorizontalScrollBarPolicy c policy))

(defn- set-vertical-scroll-bar-policy [c ctx policy]
  (.setVerticalScrollBarPolicy c policy))

(defn- get-default-size [c]
  (let [preferred-size (.getPreferredSize c)
        _ (.setPreferredSize c nil)
        default-size (.getPreferredSize c)]
    (.setPreferredSize c preferred-size)
    default-size))

(defn- set-width [c ctx width]
  (let [view-width (get-in ctx [:old-view :width])
        onscreen-width (.getWidth c)
        height (-> c .getSize .getHeight)]
    (when (and (not= view-width onscreen-width) (not= onscreen-width width))
      (log/warn "onscreen width changed outside Retroact since last update. view-width =" view-width ", onscreen-width =" onscreen-width ", new-width =" width))
    (.setSize c (Dimension. (or width (.getWidth (get-default-size c))) height))))

(defn- set-some-width [c width getter-fn setter-fn]
  (let [dimension (getter-fn)
        height (.getHeight dimension)]
    (setter-fn (Dimension. width height)))
  (when (instance? Window c)
    (.pack c)))

(defn- set-some-height [c height getter-fn setter-fn]
  (let [dimension (getter-fn)
        width (.getWidth dimension)]
    (setter-fn (Dimension. width height)))
  (when (instance? Window c)
    (.pack c)))

(defn- set-max-width [c ctx max-width]
  (set-some-width c max-width #(.getMaximumSize c) #(.setMaximumSize c %)))

(defn- set-min-width [c ctx min-width]
  (set-some-width c min-width #(.getMinimumSize c) #(.setMinimumSize c %)))

(defn- set-preferred-width [c ctx preferred-width]
  (set-some-width c preferred-width #(.getPreferredSize c) #(.setPreferredSize c %)))

(defn- set-max-height [c ctx max-height]
  (set-some-height c max-height #(.getMaximumSize c) #(.setMaximumSize c %)))

(defn- set-min-height [c ctx min-height]
  (set-some-height c min-height #(.getMinimumSize c) #(.setMinimumSize c %)))

(defn- set-preferred-height [c ctx preferred-height]
  (set-some-height c preferred-height #(.getPreferredSize c) #(.setPreferredSize c %)))

(defn set-height [c ctx height]
  (let [view-height (get-in ctx [:old-view :height])
        onscreen-height (.getHeight c)
        width (-> c .getSize .getWidth)]
    (when (and (not= view-height onscreen-height) (not= onscreen-height height))
      (log/warn "onscreen height changed outside Retroact since last update. view-height =" view-height ", onscreen-height =" onscreen-height ", new-height =" height))
    (.setSize c (Dimension. width (or height (.getHeight (get-default-size c)))))))

(defn set-on-close [c ctx action]
  (if (contains? on-close-action-map action)
    (.setDefaultCloseOperation c (on-close-action-map action))
    (do
      (.setDefaultCloseOperation c WindowConstants/DO_NOTHING_ON_CLOSE)
      (.addWindowListener c (listeners/proxy-window-listener-close (:app-ref ctx) c action)))))


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
(defmethod get-existing-children JScrollPane [_]
  (log/error "cannot get children for JScrollPane"))

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
(defmethod add-new-child-at JScrollPane [_ child _ _]
  (log/error "cannot add child to JScrollPane" child))

(defmulti remove-child-at (fn [container index] (class container)))
(defmethod remove-child-at Container [c index] (.remove c index))
(defmethod remove-child-at RootPaneContainer [c index] (.remove (.getContentPane c) index))
(prefer-method remove-child-at RootPaneContainer Container)
(defmethod remove-child-at JList [jlist index] (println "JList remove-child-at not implemented yet"))
(defmethod remove-child-at JTabbedPane [tabbed-pane index]
  (.removeTabAt tabbed-pane index))
(defmethod remove-child-at JMenu [menu index]
  (.remove menu ^int index))
(defmethod remove-child-at JScrollPane [_ index]
  (log/error "cannot remove child from JScrollPane. index =" index))

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
(defmethod get-child-at JScrollPane [_ index]
  (log/error "cannot get child from JScrollPane. index =" index))
; end :contents fns


(defmulti get-child-data (fn [onscreen-component index] (class onscreen-component)))
(defmethod get-child-data JList [onscreen-component index]
  (let [child (get-child-at onscreen-component index)]
    (util/get-client-prop child "data")))


; TODO: oops... I just added all the :class key-value pairs, but perhaps unnecessarily. I did that so I could match
; the children to the virtual dom, but I don't need to do that. The diff will be between two virtual doms. After the
; diff is complete I should have a list of deletions, insertions, and changes (apply attributes) at particular indices.
; I won't need to look at the class or identity of the actual components, I can just remove the necessary indices, add
; the necessary indices, and update attributes.
(def class-map
  {:default                    (fn default-swing-component-constructor [ctx]
                                 (log/warn "using default constructor to generate a JPanel. :class =" (get-in ctx [:view :class]))
                                 (JPanel.))
   :border-layout              BorderLayout
   :box-layout                 create/create-box-layout
   :button                     JButton
   :card-layout                CardLayout
   :check-box                  JCheckBox
   :check-box-menu-item        JCheckBoxMenuItem
   :combo-box                  create-jcombobox
   :dialog                     create/create-jdialog                            ; uses :owner attr in constructor
   :empty-border               borders/create-empty-border
   :file-chooser               JFileChooser
   :file-name-extension-filter create/create-file-name-extension-filter
   :frame                      JFrame
   :horizontal-glue            create/create-horizontal-glue
   :label                      JLabel
   :list                       create-jlist
   :menu                       JMenu
   :menu-item                  JMenuItem
   :mig-layout                 MigLayout
   :panel                      JPanel
   :popup-menu                 JPopupMenu
   :progress-bar               JProgressBar
   :option-pane                create/create-joption-pane
   :overlay-layout             create/create-overlay-layout
   :scroll-pane                JScrollPane
   :separator                  JSeparator
   :split-pane                 JSplitPane
   :tabbed-pane                JTabbedPane
   :table                      create-jtable
   :text-area                  create/create-jtext-area
   :text-field                 JTextField
   :text-pane                  JTextPane
   :titled-border              TitledBorder
   :toggle-button              JToggleButton
   :tool-bar                   JToolBar
   :tree                       create-jtree
   :window                     create/create-window})

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

(defn- set-orientation [c ctx orientation]
  (if (instance? JSplitPane c)
    (.setOrientation c (pane-orientation-map orientation))
    (.setOrientation c (orientation-map orientation))))

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

(defn- set-list-data [c ctx list-data]
  (let [model (.getModel c)]
    (.removeAllElements model)
    (when list-data (.addAll model list-data))))

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

(defn set-opaque [c ctx opaque]
  (set-property-on-root-pane c opaque #(.isOpaque %) #(.setOpaque %1 %2)))

(defn set-row-selection-interval [c ctx [start end]]
  (if (instance? JScrollPane c)
    (set-row-selection-interval (get-scrollable-view c) ctx [start end])
    (if (instance? JTable c)
      (run-on-toolkit-thread
        #(do
           (if (and start end)
             (.setRowSelectionInterval c start end)
             (.clearSelection c)))))))

(defn set-text [text-component ctx text]
  ; This is a complex update because it's trying to avoid infinite cycles between the EDT and Retroact event loops.
  (let [text-in-field (str (.getText text-component))
        text-state (str text)
        text-prop (str (util/get-client-prop text-component "text"))
        event-id [(util/get-comp-id text-component) :text]]
    (cond
      (= text-prop text-state) (do #_nothing)
      (and (not (= text-prop text-state))
           (= text-prop text-in-field)) (do (swap! silenced-events conj event-id)
                                            (.setText text-component text-state)
                                            (util/set-client-prop text-component "text" text-state)
                                            (swap! silenced-events disj event-id))
      (and (not (= text-prop text-state))
           (not (= text-prop text-in-field))) (do (util/set-client-prop text-component "text" text-state))
      :else (log/error "set-text: should never reach here!"))))

(defn on-change [c ctx change-handler]
  (listeners/remove-listener c ChangeListener RetroactSwingOnChange)
  (when change-handler
    (.addChangeListener c (listeners/proxy-change-listener (fn [ce] (change-handler ctx ce))))))

(defn- on-horizontal-scroll [c ctx scroll-handler]
  (on-change
    (.getModel (.getHorizontalScrollBar c))
    ctx scroll-handler))

(defn- on-vertical-scroll [c ctx scroll-handler]
  (on-change
    (.getModel (.getVerticalScrollBar c))
    ctx scroll-handler))

(defn on-component-resize [c ctx component-resize-handler]
  (listeners/remove-listener c ComponentListener RetroactSwingOnComponentResize)
  (when component-resize-handler
    (.addComponentListener c (listeners/proxy-component-resize-listener (fn [ce] (component-resize-handler ctx ce))))))

(defn on-component-hidden [c ctx handler]
  (listeners/remove-listener c ComponentListener RetroactSwingOnComponentHidden)
  (when handler
    (.addComponentListener c (listeners/proxy-component-hidden-listener (fn [ce] (handler ctx ce))))))

(defn on-component-shown [c ctx handler]
  (listeners/remove-listener c ComponentListener RetroactSwingOnComponentShown)
  (when handler
    (.addComponentListener c (listeners/proxy-component-shown-listener (fn [ce] (handler ctx ce))))))

(defn on-key-pressed [c ctx handler]
  (listeners/remove-listener c KeyListener RetroactSwingOnKeyPressed)
  (when handler
    ; TODO: passing the context here (ctx) is incorrect and I do it all over the place. The problem is that the context does not change because it has been captured in a closure.
    (.addKeyListener c (listeners/proxy-key-pressed-listener (fn [e] (handler ctx e))))))

(defn- on-drag
  "When handler returns a truthy value, the value is treated as the transferable and DragSource.startDrag is called."
  [c {:keys [app-ref] :as ctx} handler]
  (let [c (get-scrollable-view c)
        drag-source (DragSource.)
        local-ctx (assoc ctx :onscreen-component c)]
    ;TODO: allow the user to decide what DnD action to use. Defaulting to ACTION_COPY now.
    (.createDefaultDragGestureRecognizer
      drag-source c DnDConstants/ACTION_COPY
      (reify DragGestureListener
        (dragGestureRecognized [this drag-gesture-event]
          (when-let [transferable (handler (assoc local-ctx :app-val @app-ref) drag-gesture-event)]
            (.startDrag drag-gesture-event DragSource/DefaultCopyDrop transferable (proxy [DragSourceAdapter] []))))))))

(defn- on-drag-over [c ctx handler])

(defn- on-drop [c {:keys [app-ref] :as ctx} handler]
  (let [local-ctx (assoc ctx :onscreen-component c)
        drop-target (DropTarget. c DnDConstants/ACTION_COPY
                                 (proxy [DropTargetAdapter] []
                                   (drop [drop-event] (handler (assoc local-ctx :app-val @app-ref) drop-event))))]))

(defn- set-drag-enabled [c ctx drag-enabled]
  (.setDragEnabled (get-scrollable-view c) drag-enabled))

(defn- set-transfer-handler [c ctx transfer-handler]
  (.setTransferHandler (get-scrollable-view c) transfer-handler))

(defn on-property-change [c ctx property-change-handler]
  (listeners/remove-listener c PropertyChangeListener RetroactSwingOnPropertyChange)
  (when property-change-handler
    (.addPropertyChangeListener c (listeners/proxy-property-change-listener (fn [pce] (property-change-handler ctx pce))))))

(defmulti on-selection-change (fn [c _ _] (class c)))

(defmethod on-selection-change JComboBox [c ctx selection-change-handler]
  (listeners/remove-listener c ActionListener RetroactSwingOnSelectionChange)
  (when selection-change-handler
    (.addActionListener c (listeners/proxy-combo-box-selection-listener
                            ctx
                            (fn [ae]
                              (let [selected-item (.getSelectedItem (.getModel c))]
                                (selection-change-handler (create-handler-context ctx c) ae selected-item)))))))

(defmethod on-selection-change JTree [c ctx selection-change-handler]
  (listeners/remove-listener c TreeSelectionListener RetroactSwingOnSelectionChange)
  (when selection-change-handler
    (.addTreeSelectionListener c (listeners/proxy-tree-selection-listener ctx selection-change-handler))))

(defmethod on-selection-change JList [c ctx selection-change-handler]
  (listeners/remove-listener c ListSelectionListener RetroactSwingOnSelectionChange)
  (when selection-change-handler
    (.addListSelectionListener c (listeners/proxy-list-selection-listener ctx selection-change-handler))))

(defmethod on-selection-change JTable [table ctx selection-change-handler]
  (let [selection-model (.getSelectionModel table)]
    (listeners/remove-listener selection-model ListSelectionListener RetroactSwingOnSelectionChange)
    (when selection-change-handler
      (.addListSelectionListener selection-model (listeners/proxy-tree-list-selection-listener ctx table selection-change-handler)))))

(defmethod on-selection-change JScrollPane [c ctx selection-change-handler]
  (on-selection-change (get-scrollable-view c) ctx selection-change-handler))

(defn on-text-change [c ctx text-change-handler]
  (listeners/remove-listener (.getDocument c) DocumentListener RetroactSwingOnTextChange)
  #_(doseq [dl (vec (-> c .getDocument .getDocumentListeners))]
      (when (instance? DocumentListener dl) (.removeDocumentListener (.getDocument c) dl)))
  (when text-change-handler
    (.addDocumentListener
      (.getDocument c)
      (listeners/proxy-document-listener-to-text-change-listener
        c
        (fn text-change-handler-clojure [doc-event]
          (try
            (text-change-handler (create-handler-context ctx c) doc-event (.getText c))
            (catch ArityException ae1
              (log/warn "two and four arg version of :on-text-change handler deprecated, please update to"
                        "three args with the context, doc-event, and new text as the args")
              (try
                (text-change-handler (:app-ref ctx) c doc-event (.getText c))
                (catch ArityException ae
                  (text-change-handler (:app-ref ctx) (.getText c)))))))))))

(defn on-set-value-at
  "The set-value-at-handler has args [app-ref old-item new-value row col] where row and col are the row and column of
   the table where the user performed an edit on the cell at row and column, old-item is the item represented at that
   row (this is in domain space and may be a map, vec, set, or any arbitrary data structure - maps are typical), and
   new-value is the value of the cell after the user completed the edit. The set-value-at-handler must determine where
   in the item data structure the new-value must be set and how to perform that set in app-ref. This is, in a sense the
   inverse of the row-fn in set-row-fn. See examples.todo for an example handler.

   NOTE: I cannot remember why I created this. I believe it is to handle edits of the cells and pass the updated value
   back to the app for updating the app state. TODO: Verify this and document it."
  [c ctx set-value-at-handler]
  (safe-table-model-set c (memfn setSetValueAtFn set-value-at-fn)
                        (fn set-value-at-fn [old-item new-value row col]
                          (set-value-at-handler (:app-ref ctx) (util/get-view c) old-item new-value row col))))

(defn- on-focus-gained
  [c ctx focus-gained-handler]
  (listeners/remove-listener c FocusListener RetroactSwingOnFocusGained)
  (when focus-gained-handler
    (.addFocusListener c (listeners/proxy-focus-gained (create-handler-context ctx c) focus-gained-handler))))

(defn- on-focus-lost
  [c ctx focus-lost-handler]
  (listeners/remove-listener c FocusListener RetroactSwingOnFocusLost)
  (when focus-lost-handler
    (.addFocusListener c (listeners/proxy-focus-lost (create-handler-context ctx c) focus-lost-handler))))

(defn on-click
  [c ctx click-handler]
  (if (not (instance? JScrollPane c))
    (do
      (listeners/remove-listener c MouseListener RetroactSwingOnClick)
      (when click-handler
        (.addMouseListener
          c (listeners/proxy-mouse-listener-click (:app-ref ctx) click-handler))))
    (on-click (get-scrollable-view c) ctx click-handler)))

(defn on-mouse-wheel-moved
  [c ctx wheel-moved-handler]
  (listeners/remove-listener c MouseWheelListener RetroactSwingOnMouseWheelMoved)
  (when wheel-moved-handler
    (.addMouseWheelListener
      c (listeners/proxy-mouse-listener-wheel-moved
          (create-handler-context ctx c)
          wheel-moved-handler))))


; *** :render and :class are reserved attributes, do not use! ***
; Changes to :render do not update the UI. Changes to :class cause a disposal of previous component and creation of
; an entirely new one. Changes to :render perhaps should do the same, but ideally, should update the underlying
; rendering state.
(def attr-appliers
  {:accelerator            set-accelerator                  ; Java KeyStroke instances have identity and value congruence
   :accessory              {:set (fn set-accessory [c ctx accessory] (.setAccessory c accessory))
                            :get (fn get-accessory [c ctx] (.getAccessory c))}
   :alignment-x            (fn set-alignment-x [c ctx alignment] (.setAlignmentX c alignment))
   :alignment-y            (fn set-alignment-y [c ctx alignment] (.setAlignmentY c alignment))
   :always-on-top          (fn set-always-on-top [c ctx always-on-top] (.setAlwaysOnTop c always-on-top))
   :approve-button-text    (fn set-approve-button-text [c ctx text] (.setApproveButtonText c text))
   :background             set-background
   :border                 borders/set-border               ; maps to border factory methods, see set-border
   :caret-position         (fn set-caret-position [c ctx position] (.setCaretPosition c position))
   :client-properties      update-client-properties
   :color                  set-foreground
   :columns                set-columns
   :constraints            set-constraints
   :content-area-filled    (fn set-content-area-filled [c ctx filled] (.setContentAreaFilled c filled))
   :content-type           (fn set-content-type [c ctx content-type] (.setContentType c content-type))
   :description            {:recreate [FileNameExtensionFilter]}
   :dialog-type            (fn set-dialog-type [c ctx dialog-type] (.setDialogType c dialog-type))
   :editable               set-editable
   :enabled                set-enabled
   :extensions             {:recreate [FileNameExtensionFilter]}
   :file-filter            {:set (fn set-file-filter [c ctx file-filter] (log/info "setting file filter to " file-filter) (.setFileFilter c file-filter))
                            :get (fn get-file-filter [c ctx] (.getFileFilter c))}
   :focusable              (fn set-focusable [c ctx focusable] (.setFocusable c focusable))
   :font                   (fn set-font [c ctx font] (.setFont c font))
   :font-size              (fn set-font-size [c ctx size] (let [f (.getFont c)] (.setFont c (.deriveFont ^Font f ^int (.getStyle f) ^float size))))
   :font-style             (fn set-font-style [c ctx style] (let [f (.getFont c)] (.setFont c (.deriveFont ^Font f ^int style ^float (.getSize f)))))
   :icon                   (fn set-icon [c ctx icon] (.setIcon c icon))
   :data                   (fn set-retroact-data [c ctx data] (util/set-client-prop c "data" data))
   :layout                 {:set (fn set-layout [c ctx layout] (.setLayout c layout))
                            :get (fn get-layout [c ctx]
                                   (cond
                                     (instance? RootPaneContainer c) (.getLayout (.getContentPane c))
                                     :else (.getLayout c)))}
   :line-wrap              (fn set-line-wrap [c ctx line-wrap] (.setLineWrap c line-wrap))
   ; The following doesn't appear to work. It may be that macOS overrides margins. Try using empty border.
   :margin                 (fn set-margin [c ctx insets] (.setMargin c insets))
   :modal                  (fn set-modal [c ctx modal] (.setModal c modal))
   :name                   (fn set-name [c ctx name] (.setName c name))
   :on-close               set-on-close
   :opaque                 set-opaque
   :owner                  {:recreate [JDialog]}
   :row-selection-interval set-row-selection-interval
   :selected               (fn set-selected [c ctx selected?]
                             (.setSelected c (boolean selected?)))
   :selected-index         {:deps [:contents]
                            :fn   (fn set-selected-index [c ctx index] (.setSelectedIndex c index))}
   :selection-mode         (fn set-selection-mode [c ctx selection-mode] (.setSelectionMode ^JList c selection-mode))
   :text                   set-text
   :title                  set-title
   :tool-tip-text          set-tool-tip-text
   :visible                (fn set-visible [c ctx visible] (.setVisible c (boolean visible)))
   :wrap-style-word        (fn set-wrap-style-word [c ctx wrap-style-word] (.setWrapStyleWord c wrap-style-word))

   ; size related attr
   :width                  set-width
   :max-width              set-max-width
   :min-width              set-min-width
   :preferred-width        set-preferred-width
   :height                 set-height
   :max-height             set-max-height
   :min-height             set-min-height
   :preferred-height       set-preferred-height

   ; miglayout constraint attr
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
   :orientation            set-orientation
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
   ; List attr appliers
   :list-data              set-list-data
   ; Progress bar appliers
   :maximum                (fn set-maximum [c ctx maximum] (.setMaximum c maximum))
   :minimum                (fn set-minimum [c ctx minimum] (.setMinimum c minimum))
   :value                  (fn set-value [c ctx value] (.setValue c value))
   :string                 (fn set-string [c ctx string] (.setString c string)) ; maybe make this part of :text??
   ; End progress bar appliers
   ; Table attr appliers
   :column-names           set-column-names
   :row-editable-fn        set-row-editable-fn
   :row-fn                 set-row-fn
   :table-data             set-table-data
   ; Tree attr appliers
   ; TODO: implement this to set the scrolls on expand of JTree
   ;:scrolls-on-expand      set-scrolls-on-expand
   :tree-render-fn         set-tree-render-fn
   :tree-model-fn          set-tree-model-fn
   :tree-selection-fn      set-tree-selection-fn
   :tree-scroll-path-fn    set-tree-scroll-path-fn
   :tree-data              set-tree-data
   :toggle-click-count     set-tree-toggle-click-count
   ; End tree attr appliers
   ; Scroll pane appliers
   :horizontal-scroll-bar  set-horizontal-scroll-bar
   :horizontal-scroll-bar-policy set-horizontal-scroll-bar-policy
   :on-vertical-scroll     on-vertical-scroll
   :on-horizontal-scroll   on-horizontal-scroll
   :vertical-scroll-bar    set-vertical-scroll-bar
   :vertical-scroll-bar-policy set-vertical-scroll-bar-policy
   :viewport-border        borders/set-viewport-border
   :viewport-view          {:set (fn set-viewport-view [c ctx component] (.setViewportView ^JScrollPane c component))
                            :get (fn get-viewport-view [c ctx] (.getView (.getViewport c)))}
   ; End scroll pane appliers

   ; Event attrs. :on-*
   :on-action              (fn on-action [c ctx action-handler]
                             (listeners/remove-listener c ActionListener RetroactSwingOnAction)
                             (when action-handler
                               (.addActionListener c (listeners/proxy-action-listener
                                                       (fn action-handler-clojure [action-event]
                                                         (action-handler (:app-ref ctx) action-event))))))
   :on-change              on-change
   :on-component-resize    on-component-resize
   :on-component-hidden    on-component-hidden
   :on-component-shown     on-component-shown
   :on-focus-gained        on-focus-gained
   :on-focus-lost          on-focus-lost
   :on-key-pressed         on-key-pressed
   :on-property-change     on-property-change
   :on-selection-change    on-selection-change
   :on-text-change         on-text-change
   :on-set-value-at        on-set-value-at
   ; Mouse listeners
   :on-click               on-click
   :on-mouse-wheel-moved   on-mouse-wheel-moved
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
   ; Children and Component appliers (not all are here, but if they don't fit in another section, they're here)
   ; Popup-menu
   :popup-menu             {:get (fn get-popup-menu [c ctx] (.getComponentPopupMenu c))
                            :set (fn set-popup-menu [c ctx popup-menu] (.setComponentPopupMenu c popup-menu))}
   ; Menus
   :menu-bar               {:get-existing-children mb/get-existing-children
                            :add-new-child-at      mb/add-new-child-at
                            :remove-child-at       mb/remove-child-at
                            :get-child-at          mb/get-child-at}
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
   :assoc-view                `util/assoc-view
   :get-view                  `util/get-view
   :assoc-ctx                 `util/assoc-ctx
   :get-ctx                   `util/get-ctx
   :clear-onscreen-component  `util/clear-onscreen-component
   :redraw-onscreen-component `redraw-onscreen-component
   :class-map                 `class-map
   :run-on-toolkit-thread     `run-on-toolkit-thread})
