(ns swing-react.core
  (:require [clojure.data :refer [diff]]
            [clojure.pprint :refer [pprint]]
            [swing-react.jlist :refer [create-jlist]]
            [clojure.tools.logging :as log]
            [manifold.stream :as ms]
            [swing-react.manifold-support :as mss])
  (:import (java.awt Color Button Container Component Dimension)
           (javax.swing JFrame JLabel JButton JTextField JTree JList DefaultListModel JCheckBox SwingUtilities)
           (net.miginfocom.swing MigLayout)
           (java.awt.event ActionListener)
           (javax.swing.event DocumentListener)))

; Open questions:
;
;  - How to resolve arguments to appliers that need to be objects... like layout managers. And colors for that matter.
;    Currently, I resolve colors by requiring them to be hex values and instantiate the color class with a hex value.
;    But there are many ways to get colors and this is limiting for people used to the full Java library. A resolver
;    for the Color class and the ability to do resolution on arbitrary values (not just components in contents and the
;    argument of build-ui) would be nice.
;  - :contents is not actually special. It can be treated as an attribute that takes an array of objects.
;    - attributes can take a primitive value or an object.
;    - in this way, everything becomes orthogonal (normalized).
;  - :component is still different thought. It's part of the type or identity. Something that is persistent.
;    v rename :component to :class. In this way, we can also use this for MigLayout and other classes.
;
;

; The keys fall into categories. I can separate them and group them into sub keys or just flatten them and sort them
; out while iterating over the map.
;
; :component represents the type of the component which is part of the object's identity and is going to be persistent.
; :background is an attribute that may change.
; :contents point to children objects that have their own identities and attributes (and possibly children). They make
;           this a tree.
;
; I can put all the attributes in a key called :attributes or :attrs or something.
; Later there may also be other meta data keys for things like an id of the component in the case of lists of things.
; (think React's need for assigning ids to know if something was added, removed, or moved in a list since everything
; else about it may change).
;
; For now, I'll stick with the flat map and see how it goes.

(declare build-ui)

(def app-refs (atom []))

(def on-close-action-map
  {:dispose JFrame/DISPOSE_ON_CLOSE
   :do-nothing JFrame/DO_NOTHING_ON_CLOSE
   :exit JFrame/EXIT_ON_CLOSE
   :hide JFrame/HIDE_ON_CLOSE})

(defn reify-action-listener [action-handler]
  (reify ActionListener
    (actionPerformed [this action-event]
      (action-handler action-event))))

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
(defmethod get-existing-children Container [c] (.getComponents (.getContentPane c)))
(defmethod get-existing-children JList [jlist]
  (let [model (.getModel jlist)]
    (println "JList get-existing-children not implemented")
    (-> model
        (.elements)
        (enumeration-seq)
        (vec))))

(defmulti add-new-child-at (fn [container child _ _] (class container)))
(defmethod add-new-child-at Container [^Container c ^Component child constraints index] (.add ^Container (.getContentPane c) child constraints ^int index))
(defmethod add-new-child-at JList [^JList jlist ^Component child constraints index]
  (let [model (.getModel jlist)]
    (println "jlist add-new-child-at" index "(model class:" (class model) " size =" (.getSize model) ")" child)
    (.add model index child)
    child))

(defmulti remove-child-at (fn [container index] (class container)))
(defmethod remove-child-at Container [c index] (.remove (.getContentPane c) index))
(defmethod remove-child-at JList [jlist index] (println "JList remove-child-at not implemented yet"))

(defmulti get-child-at (fn [container index] (class container)))
(defmethod get-child-at Container [c index] (.getComponent (.getContentPane c) index))
(defmethod get-child-at JList [jlist index]
  (let [child
        (-> jlist
            (.getModel)
            (.getElementAt index))]
    (println "jlist get-child-at:" child)
    child))
; end :contents fns


; TODO: oops... I just added all the :class key-value pairs, but perhaps unnecessarilly. I did that so I could match
; the children to the virtual dom, but I don't need to do that. The diff will be between two virtual doms. After the
; diff is complete I should have a list of deletions, insertions, and changes (apply attributes) at particular indices.
; I won't need to look at the class or identity of the actual components, I can just remove the necessary indices, add
; the necessary indices, and update attributes.
(def class-map
  {:button     #(JButton.)
   :frame      #(JFrame.)
   :label      #(JLabel.)
   :check-box  #(JCheckBox.)
   :text-field #(JTextField.)
   :list       create-jlist
   :mig-layout #(MigLayout.)}
  #_{:frame      {:constructor #(JFrame.) :class JFrame}
     :label      {:constructor #(JLabel.) :class JLabel}
     :mig-layout {:constructor #(MigLayout.) :class MigLayout}
     :button     {:constructor #(JButton.) :class JButton}})

(defn text-changed? [old-text new-text]
  (not
    (or (= old-text new-text)
        ; empty string and nil are treated the same. See JTextComponent.setText().
        (and (nil? old-text) (empty? new-text))
        (and (empty? old-text) (nil? new-text)))))

(def attr-appliers
  {:background         (fn set-background [c ctx color] (cond
                                                          (instance? JFrame c) (.setBackground (.getContentPane c) (Color. color))
                                                          :else (.setBackground c (Color. color))))
   :border             (fn set-border [c ctx border] (.setBorder c border))
   ; The following doesn't appear to work. It may be that macOS overrides margins.
   :margin             (fn set-margin [c ctx insets] (.setMargin c insets))
   :height             set-height
   :layout             (fn set-layout [c ctx layout]
                         (pprint ctx)
                         #_(get-in ctx [])
                         (println "old layout:"
                                  (try (.getLayout (.getContentPane c))
                                       (catch NullPointerException e "none")))
                         (println "new layout:" layout)
                         (let [layout-component (build-ui (:app-ref ctx) layout)]
                           (println "layout component:" layout-component)
                           (println "setting layout for:" c)
                           (.setLayout c layout-component))
                         (println "layout set:" (.getLayout (.getContentPane c))))
   :opaque             (fn set-opaque [c ctx opaque] (cond
                                                       (instance? JFrame c) (.setOpaque (.getContentPane c) opaque)
                                                       :else (.setOpaque c opaque)))
   :text               (fn set-text [c ctx text]
                         #_(.printStackTrace (Exception. "stack trace"))
                         (let [old-text (.getText c)]
                           (println (str "new-text = \"" text "\" old-text = \"" old-text "\""))
                           (println (str "new-text nil? " (nil? text) " old-text nil? " (nil? old-text)))
                           (when (text-changed? old-text text)
                             (.setText c text))))
   :width              set-width
   :caret-position     (fn set-caret-position [c ctx position] (.setCaretPosition c position))
   ; TODO: if action not in on-close-action-map, then add it as a WindowListener to the close event
   :on-close           (fn on-close [c ctx action] (.setDefaultCloseOperation c (on-close-action-map action)))
   :layout-constraints (fn set-layout-constraints [c ctx constraints] (.setLayoutConstraints c constraints))
   :row-constraints    (fn set-row-constraints [c ctx constraints] (.setRowConstraints c constraints))
   :column-constraints (fn set-column-constraints [c ctx constraints] (.setColumnConstraints c constraints))
   ; All action listeners must be removed before adding the new one to avoid re-adding the same anonymous fn.
   :on-action          (fn on-action [c ctx action-handler]
                         (doseq [al (vec (.getActionListeners c))] (.removeActionListener c al))
                         (.addActionListener c (reify-action-listener (fn action-handler-clojure [action-event]
                                                                        (action-handler (:app-ref ctx) action-event)))))
   :on-text-change     (fn on-text-change [c ctx text-change-handler]
                         #_(doseq [dl (vec (-> c .getDocument .getDocumentListeners))]
                             (when (instance? DocumentListener dl) (.removeDocumentListener (.getDocument c) dl)))
                         (.addDocumentListener (.getDocument c)
                                               (reify-document-listener-to-text-change-listener
                                                 (fn text-change-handler-clojure [doc-event]
                                                   (text-change-handler (:app-ref ctx) (.getText c))))))
   ; TODO: refactor add-contents to a independent defn and check component type to be sure it's a valid container.
   ;  Perhaps pass in the map in addition to the component so that we don't have to use `instanceof`?
   ; TODO:
   ; - specify getter for existing child components
   ; - specify fn for adding new child component at specified index
   ; - no need to specify how to update a child component... that is just as if it was a root component.
   ; - no need to specify how to create a child component... that is also as if it was a root component.
   :contents           {:get-existing-children get-existing-children #_(fn get-existing-children [c] (.getComponents (.getContentPane c)))
                        :add-new-child-at      add-new-child-at #_(fn add-new-child-at [^Container c ^Component child constraints index] (.add ^Container (.getContentPane c) child constraints ^int index))
                        :remove-child-at       remove-child-at #_(fn remove-child-at [c index] (.remove (.getContentPane c) index))
                        :get-child-at          get-child-at #_(fn get-child-at [c index] (.getComponent (.getContentPane c) index))}
   })

(defn children-applier?
  [attr-applier]
  (and (map? attr-applier)
       (contains? attr-applier :get-existing-children)
       (contains? attr-applier :add-new-child-at)
       (contains? attr-applier :remove-child-at)
       (contains? attr-applier :get-child-at)))

(declare apply-attributes)

(defn pad [col length]
  (vec (take length (concat col (repeat nil)))))

(defn apply-children-applier
  [attr-applier component ctx attr old-view new-view]
  (let [add-new-child-at (:add-new-child-at attr-applier)
        get-child-at (:get-child-at attr-applier)
        old-children (get old-view attr)
        new-children (get new-view attr)
        ;_ (println "old children:")
        ;_ (pprint old-children)
        ;_ (println "new children:")
        ;_ (pprint new-children)
        max-children (max (count old-children) (count new-children))]
    (doseq [[old-child new-child index]
            (map vector
                 (pad old-children max-children)
                 (pad new-children max-children)
                 (range))]
      #_(println "child component (" index "):" (get-child-at component index))
      (cond
        (nil? old-child) (add-new-child-at component (build-ui (:app-ref ctx) new-child) (:constraints new-child) index)

        ; TODO: remove children that aren't in new-children
        ; TODO: check that identity (class name) match before applying attributes, otherwise, remove and add new child
        :else (apply-attributes {:component (get-child-at component index) :app-ref (:app-ref ctx) :old-view old-child :new-view new-child})
        ))))

(defn apply-attributes
  [{:keys [component app-ref old-view new-view]}]
  (if (not (= old-view new-view))                           ; short circuit - do nothing if old and new are equal.
    (doseq [attr (set (keys new-view))]
      (when-let [attr-applier (get attr-appliers attr)]
        #_(println "attr-applier" attr-applier)
        (cond
          ; TODO: these should not be done within an atom swap! Because they are side effects. Instead, accumulate them
          ; as a list of fns to apply after the swap is complete. In addition, the new view state should be kept with
          ; this list of fns and the atom should be updated with the new view state when a successful update occurs
          ; tic-tok cycle of swaps on the atom...
          ; TODO: mutual recursion here could cause stack overflow for deeply nested UIs? Will a UI ever be that deeply
          ; nested?? Still... I could use trampoline and make this the last statement. Though trampoline may not help
          ; since apply-children-applier iterates over a sequence and calls apply-attributes. That iteration would have
          ; to be moved to apply-attributes or recursive itself.
          (children-applier? attr-applier) (apply-children-applier attr-applier component {:app-ref app-ref} attr old-view new-view)
          ; Assume attr-applier is a fn and call it on the component.
          :else (when (not (= (get old-view attr) (get new-view attr)))
                  #_(println "applying attribute " attr " with value " (get new-view attr))
                  (println "applying" attr-applier (get new-view attr))
                  (attr-applier component {:app-ref app-ref :new-view new-view} (get new-view attr)))))))
  component)

(defn instantiate-class
  [ui]
  (let [id (:class ui)
        ;_ (println "instantiating" id)
        constructor (get-in class-map [(:class ui) #_:constructor])
        component (constructor)]
    #_(println "component: " component)
    component))

; TODO: perhaps build-ui is more like build-object because :mig-layout is not a UI. And the way things are setup,
; any object can be built with this code.
(defn build-ui
  "Take a view and realize it."
  [app-ref view]
  (let [component (instantiate-class view)]
    (apply-attributes {:component component :app-ref app-ref :new-view view})))

(defn update-view [app-ref app-value]
  (let [render (get-in app-value [:app :render])
        _ (log/info "app-ref:" app-ref)
        _ (log/info "render:" render)
        new-view (render app-ref app-value)]
    (assoc app-value :current-view new-view)))

; TODO: because this is a dropping stream and the app-ref is put into this stream, only one app can run at a time.
; Fixed. I create a stream per app. I still put the app-ref on the stream... though technically this shouldn't even
; be necessary anymore because there is also a thread per app.
#_(def update-view-stream (mss/dropping-stream 1))

(defn update-view-consumer [deferred-app-ref]
  ; No need to worry about async changes to app-ref, just get the most recent val and use it. If there are async changes
  ; they will be detected on subsequent calls. Subsequent calls will be made because they will be queued up in the
  ; stream. If the render fn returns the same view data then no need to update UI. If render fn returns diff view data
  ; then UI needs to be updated again, no problem.
  (let [_ (log/info "update-view-consumer got" deferred-app-ref)
        app-ref @deferred-app-ref
        app-val @app-ref
        render (get-in app-val [:app :render])
        old-view (:current-view app-val)
        new-view (render app-ref app-val)]
    (if-let [root-component (:root-component app-val)]
      ; root component exists, update it
      ; TODO: somehow do the following two atomically. And only if the UI update succeeds, update the app-ref.
      ; TODO: can UI update be rolled back?? That would only be possible if UI state is read from Swing and not assumed
      ; based on :current-view. I currently assume UI state from :current-view.
      ; Perhaps it can be multi-staged: assume for performance, if fail, read from Swing, if still fail, render from
      ; scratch. If still fail... fail back to previous.
      (do (apply-attributes {:app-ref app-ref :component root-component :old-view old-view :new-view new-view})
          #_(pprint new-view)
          (swap! app-ref assoc :current-view new-view))
      ; root component does not exist, create it and associate it ("mount" it)
      (let [root-component (build-ui app-ref new-view)]
        (swap! app-ref assoc :root-component root-component :current-view new-view))))
  )

; TODO: start this in a thread. Hmmm... using ms/consume should be enough.
(defn watch-for-update-view [app-ref]
  (let [update-view-stream (get-in @app-ref [:swing-react :update-view-stream])
        thread (Thread. ^Runnable
                        (fn update-view-consumer-fn []
                          (loop []
                            (let [dval (ms/take! update-view-stream)]
                              (update-view-consumer dval))
                            (recur))))]
    (.start thread)))

(def update-view-count (atom 0))
(defn app-watch
  [watch-key app-ref old-value new-value]
  (let [local-update-view-count (swap! update-view-count inc)]
    (println local-update-view-count "text from state:" (get-in new-value [:state :new-todo-item-text]) "text from label:" (get-in new-value [:current-view :contents 1 :text]))
    (do                                                       ;fn update-view-runnable []
      ; TODO: "mount" is not an appropriate term, I took this from React. "create" would be better. Think about it.
      ; Component did mount
      (when (and (not (contains? old-value :root-component))
                 (contains? new-value :root-component))
        (println local-update-view-count "LIFECYCLE: root component mounted.")
        (let [component-did-mount (get-in new-value [:app :component-did-mount])
              component (:root-component new-value)]
          (component-did-mount component app-ref new-value)))
      ; Update view
      (when (not= (:state old-value) (:state new-value))
        (println local-update-view-count "LIFECYCLE: state changed. updating view.")
        (let [update-view-stream (get-in @app-ref [:swing-react :update-view-stream])]
          (ms/put! update-view-stream app-ref))
        ))))

; for debugging
(defn print-components [root]
  (when (instance? Container root)
    (doseq [child (.getComponents root)]
      (println child)
      (print-components child))))

; for debugging
(defn find-component
  ([children root predicate-fn]
   (println "  iterating over children" children)
   (let [matching-child (find-component (first children) predicate-fn)]
     (cond
       matching-child matching-child
       (< 1 (count children)) (find-component (rest children) root predicate-fn)
       :else nil)))
  ([root predicate-fn]
   (println "testing" root)
   (cond
     (nil? root) nil
     (predicate-fn root) root
     (instance? Container root) (find-component (.getComponents root) root predicate-fn)
     :else nil)))

(defn run-app
  "Run the application defined by app. Once the application is started run-app will return. Optional props may be passed
  as the second arg and will be passed through to the app constructor."
  ([app] (run-app app {}))
  ([app props]
   (log/info "run-app started, test logger works")
   (let [constructor (get app :constructor (fn default-constructor [props] {}))
         app-ref (atom {:swing-react {:update-view-stream (mss/dropping-stream 1)}})]
     ; Store app-ref in global app-refs vector for use on repl and debugging.
     (swap! app-refs conj app-ref)
     (watch-for-update-view app-ref)
     (add-watch app-ref :swing-react-watch app-watch)
     (swap! app-ref assoc
            :state (constructor props)                      ; domain state
            :app app)
     )))
