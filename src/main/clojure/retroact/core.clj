(ns retroact.core
  (:require [clojure.core.async :refer [>! alt!! alts!! buffer chan go sliding-buffer]]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :as log]
            [retroact.jlist :refer [create-jlist]])
  (:import (java.awt Color Component Container Dimension)
           (java.awt.event ActionListener)
           (javax.swing JButton JCheckBox JFrame JLabel JList JPanel JTextField)
           (javax.swing.event DocumentListener)
           (net.miginfocom.swing MigLayout)))

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

(defonce app-refs (atom []))

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
; TODO: .getContentPane should probably only be called for Window (JFrame) components, not JPanel and Container
; components
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
  {:default    (fn default-swing-component-constructor []
                 (log/warn "using default constructor to generatoe a JPanel")
                 (JPanel.))
   :button     #(JButton.)
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
      (log/info "child index" index "| old-child =" old-child "| new-child =" new-child)
      (cond
        (nil? old-child) (add-new-child-at component (build-ui (:app-ref ctx) new-child) (:constraints new-child) index)

        ; TODO: remove children that aren't in new-children
        ; TODO: check that identity (class name) match before applying attributes, otherwise, remove and add new child
        :else (apply-attributes {:component (get-child-at component index) :app-ref (:app-ref ctx) :old-view old-child :new-view new-child})
        ))))

(defn apply-attributes
  [{:keys [component app-ref old-view new-view]}]
  (log/info "applying attributes" (:class new-view))
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
        constructor (get-in class-map [(:class ui) #_:constructor]
                            (fn default-onscreen-component-constructor []
                              (log/error "could not find constructor for" id "using default constructor")
                              (log/info "full view =" ui)
                              (get class-map :default)))
        component (constructor)]
    (log/info "created" id)
    #_(println "component: " component)
    component))

; TODO: perhaps build-ui is more like build-object because :mig-layout is not a UI. And the way things are setup,
; any object can be built with this code.
(defn build-ui
  "Take a view and realize it."
  [app-ref view]
  (let [component (instantiate-class view)]
    (apply-attributes {:component component :app-ref app-ref :new-view view})))

(defn update-view-main-loop [add-uv-chan]
  ; What I want to do:
  ; Store a local vector of update-view-stream's and use those streams with core.async (I'm going to change to
  ; core.async, so it will be channels, not streams)
  (loop [uv-chans []]
    (alt!!
      uv-chans ([app-ref uv-chan]
                         ; do something with app-ref
                         (let [new-uv-chans (if (= uv-chan (last uv-chans))
                                              uv-chans
                                              (conj (remove (= uv-chan) uv-chans) uv-chan))]
                           (recur new-uv-chans)))
      add-uv-chan ([uv-chan] (recur (conj uv-chans uv-chan))))))

(def update-view-thread (atom nil))
(defn start-retroact-main-thread [app-ref]
  (compare-and-set!
    update-view-thread
    nil
    (let [thread (Thread. update-view-main-loop)]
      (.start thread)
      thread)))

(defn component-did-mount? [old-value new-value]
  (let [old-components (get old-value :components {})
        new-components (get new-value :components {})]
    (if (not= old-components new-components)
      (filter (fn is-component-mounted? [comp]
                (let [comp-id (:id comp)
                      old-onscreen-comp (get-in old-components [comp-id :onscreen-component])
                      onscreen-comp (get comp :onscreen-component)]
                  (and (nil? old-onscreen-comp) (not (nil? onscreen-comp)))))
              (vals new-components)))))

(defn- missing-view?
  "Check if any components are missing their :view."
  [app-value]
  nil)

(defn- trigger-update-view [app-ref new-value]
  (go (>! (get-in new-value [:retroact :update-view-chan]) app-ref)))

; Used for debugging, to count how many times app-watch is called.
(def update-view-count (atom 0))

;TODO: rename to app-watch. There's no need for the "-2" anymore.
(defn app-watch-2
  [watch-key app-ref old-value new-value]
  (let [local-update-view-count (swap! update-view-count inc)]
    (log/info "update-view-count =" local-update-view-count)
    ; Component did mount (onscreen-component created)
    (when-let [mounted-components (component-did-mount? old-value new-value)]
      (log/info "components mounted: " mounted-components)
      (doseq [comp mounted-components]
        (log/info "calling component-did-mount for" comp)
        (let [component-did-mount (get comp :component-did-mount (fn default-component-did-mount [comp app-ref new-value]))]
          (component-did-mount (:onscreen-component comp) app-ref new-value))))
    ; Update view
    (when (or (not= (:state old-value) (:state new-value)) (not= (:components old-value) (:components new-value)))
      (trigger-update-view app-ref new-value))))

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


; TODO: FORGET IT ALL! Rather, the idea about threads and channels for the main loop.
; I just looked at how core.async does it and the delay and defprotocol fns are used along with defonce. This seems like
; a good approach for Retroact, too. I can create an executor service with a single thread (not a thread pool). Use a
; (defonce exec-service (delay ...)) to make it defined once and only when first used. @exec-service will use it. A
; similar approach may be used for starting the main loop (defonce start-main-loop (delay (main-loop))). Starting of
; the main loop will create a tiny bit of overhead each time a component is created or an app is initialized, depending
; on how I do it. But that should not be a big deal. What (main-loop) returns may actually be the channel it is
; listening on to get commands from the app. How cool is that! Perhaps I call it "main-chan" instead of
; "start-main-loop" then.

(defn- update-onscreen-component [{:keys [app-ref onscreen-component old-view new-view]}]
  (let [onscreen-component (or onscreen-component (do (log/info "building ui for" (:class new-view)) (instantiate-class new-view)))]
    ; TODO: change :component to :onscreen-component
    (apply-attributes {:app-ref app-ref :component onscreen-component :old-view old-view :new-view new-view})
    onscreen-component))

(defn- get-render-fn [comp]
  (get comp :render (fn default-render-fn [app-ref app-value]
                      (log/warn "component did not provide a render fn"))))

(defn- update-components [app-ref app components]
  (reduce-kv
    (fn render-onscreen-comp [m comp-id comp]
      (log/info "update-components comp =" comp)
      (let [render (get-render-fn comp)
            view (get-in components [comp-id :view])
            onscreen-component (get-in components [comp-id :onscreen-component])
            new-view (render app-ref app)
            onscreen-component (update-onscreen-component
                                 {:app-ref  app-ref :onscreen-component onscreen-component
                                  :old-view view :new-view new-view})]
        (assoc m comp-id (assoc comp :view new-view :onscreen-component onscreen-component))))
    {} (get app :components {})))

(defn- retroact-main-loop [retroact-cmd-chan]
  (log/info "STARTING RETROACT MAIN LOOP")
  (loop [chans [retroact-cmd-chan]
         chans->components {}]
    (let [[val port] (alts!! chans :priority true)
          cmd-name (if (vector? val) (first val) :update-view)]
      (condp = cmd-name
        :update-view (let [app-ref val
                           update-view-chan port
                           app @app-ref
                           components (get chans->components update-view-chan {})
                           next-components (update-components app-ref app components)
                           next-chans->components (assoc chans->components update-view-chan next-components)]
                       (log/info "main-loop components:      " components)
                       (log/info "main-loop next-components: " next-components)
                       ; - recur with update-view-chans that has update-view-chan at end so that we can guarantee earlier chans get read. Check alt!!
                       ;   to be sure priority is set - I remember seeing that somewhere.
                       ; - loop through all components in current app-ref.
                       ; update-view-chan has app-ref for the app at the other end of it. There is one update-view-chan per app.
                       ;
                       ; No worries about map running outside swap!. The :view inside :components vector is only updated in this
                       ; place and this place is sequential. In addition, changes to :state will always trigger another put to
                       ; update-view-chan, which will cause this code to run again. In the worst case the code runs an extra time,
                       ; not too few times.
                       ; TODO: update docs. In fact, running the map ouside swap! is critical. Because I want to update view and
                       ; the Swing components together. Once the two are updated I can call swap! and pass it the correct value of
                       ; view and swap! will just rerun until it is set. The only problem I see here is that the user will have time
                       ; to interact with the onscreen components before swap! finishes. But that really shouldn't be a problem
                       ; because the only code that would be affected by such user actions would be the code right here and since this
                       ; code is blocking until the swap! completes... there's no problem.
                       (swap! app-ref assoc :components next-components)
                       ; TODO: move update-view-chan to end of update-view-chans so that there are no denial of service issues.
                       (recur chans next-chans->components))
        :update-view-chan (let [update-view-chan (second val)] (recur (conj chans update-view-chan) chans->components))

        :shutdown (do)                                      ; do nothing, will not recur
        (log/error "unrecognized command to retroact-cmd-chan:" cmd-name))
      )))

(defn- retroact-main []
  (let [retroact-cmd-chan (chan (buffer 100))
        retroact-thread (Thread. (fn retroact-main-runnable [] (retroact-main-loop retroact-cmd-chan)))]
    (.start retroact-thread)
    retroact-cmd-chan))

(defonce retroact-cmd-chan (delay (retroact-main)))

(defn create-comp
  "Create a new top level component. There should not be many of these. This is akin to a main window. In the most
   extreme cases there may be a couple hundred of these. In a typical case there will be between one component and a
   half a dozen components. The code is optimized for a small number of top level components."
  ([app-ref comp props]
   (let [constructor (get comp :constructor (fn default-constructor [props state] state))
         comp-id (keyword (gensym "comp"))
         ; Add a unique id to ensure component map is unique. Side effects by duplicate components should
         ; generate duplicate onscreen components and we need to be sure the data here is unique. Onscreen
         ; components store Java reference in comp, but it won't be here immediately.
         comp (assoc comp :id comp-id)]
     ; No need to render comp view here because this will trigger the watch, which will render the view.
     (swap! app-ref
            (fn add-component-to-app [app]
              (let [state (get app :state {})
                    components (get app :components {})
                    _ (println "props:" props)
                    _ (println "state:" state)
                    next-state (constructor props state)
                    next-components (assoc components comp-id comp)]
                (println "next-state:" next-state)
                (println "components:      " components)
                (println "next-components: " next-components)
                (assoc app :state next-state :components next-components))))))
  ([app-ref comp] (create-comp app-ref comp {})))

; 2022-08-24
; - Create fn to create component and mount it. Tow cases:
;   - root component - return the component after it is created. The caller may then display it or add it to a legacy
;     app.
;   - child component - hmm... seems like the component should still be returned and the caller decides what to do with
;     it. If it is a Retroact parent then the child will be added... if it's a legacy app parent, then also the child
;     will be added. Or maybe for a Retroact parent the data that represents the view must be returned.
; - watch for changes on the atom
;   - each component will have a separate section for its state... right? As well as global app state??
;   - when component is mounted, a section for its state is stored in the atom.
; - rerun view render fns for each component who's state has changed.
;   - what happens if a parent's state changes and the parent reruns a child with different props and the child's state
;     had also changed the same time as the parent's state. What prevents the child from rerendering first then the
;     parent calling the child's render fn again?

; init-app may be outdated. ... I think I can still use this concept with the above 2022-08-24 ideas.
(defn init-app
  "Initialize app data as an atom and return the atom. The atom is wired in to necessary watches, core.async channels,
   and any other fns and structures to make it functional and not just a regular atom. The result is an atom that is
   reactive to changes according to FRP (Functional Reactive Programming). Components may subsequently be added to the
   app. For convenience there is a single arg version of this fn that will create a component. Although nothing prevents
   multiple calls to init-app and the code will work properly when multiple calls are made, init-app is intended to be
   called once. The resources (including threads) allocated are done so as if this is for the entire application."
  ([]
   (let [update-view-chan (chan (sliding-buffer 1))
         app-ref (atom {:retroact   {:retroact-cmd-chan @retroact-cmd-chan
                                     :update-view-chan  update-view-chan}
                        :components {}
                        :state      {}})]
     (go (>! @retroact-cmd-chan [:update-view-chan update-view-chan]))
     (swap! app-refs conj app-ref)
     ; no need to start thread here... it automatically starts when the retroact-cmd-chan is used.
     #_(start-retroact-main-thread app-ref)
     (add-watch app-ref :retroact-watch app-watch-2)
     app-ref))
  ([comp] (init-app comp {}))
  ([comp props]
   (let [app-ref (init-app)]
     (create-comp app-ref comp props)
     app-ref))
  )
