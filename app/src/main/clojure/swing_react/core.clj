(ns swing-react.core
  (:require [clojure.data :refer [diff]]
            [clojure.pprint :refer [pprint]])
  (:import (java.awt Color Button Container Component)
           (javax.swing JFrame JLabel JButton)
           (net.miginfocom.swing MigLayout)
           (java.awt.event ActionListener)))

(def default-ui
  {:class      :frame
   :background 0xff0000
   :on-close   :dispose
   :layout     {:class :mig-layout
                :layout-constraints "flowy"}
   :contents   [{:class :label
                 :text  "Hello World!"}
                {:class :button
                 :text "Say Hi!"
                 :on-action (fn say-hi [action-event] (println "hello world!"))}]
   })

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
; :contents point to children objects that have their own identities and attributes (and possibly chidren). They make
;           this a tree.
;
; I can put all the attributes in a key called :attributes or :attrs or something.
; Later there may also be other meta data keys for things like an id of the component in the case of lists of things.
; (think React's need for assigning ids to know if something was added, removed, or moved in a list since everything
; else about it may change).
;
; For now, I'll stick with the flat map and see how it goes.

(declare build-ui)

(def on-close-action-map
  {:dispose JFrame/DISPOSE_ON_CLOSE
   :do-nothing JFrame/DO_NOTHING_ON_CLOSE
   :exit JFrame/EXIT_ON_CLOSE
   :hide JFrame/HIDE_ON_CLOSE})

(defn reify-action-listener [action-handler]
  (reify ActionListener
    (actionPerformed [this action-event]
      (action-handler action-event))))

; TODO: oops... I just added all the :class key-value pairs, but perhaps unnecessarilly. I did that so I could match
; the children to the virtual dom, but I don't need to do that. The diff will be between two virtual doms. After the
; diff is complete I should have a list of deletions, insertions, and changes (apply attributes) at particular indices.
; I won't need to look at the class or identity of the actual components, I can just remove the necessary indices, add
; the necessary indices, and update attributes.
(def class-map
  {:frame      #(JFrame.)
   :label      #(JLabel.)
   :mig-layout #(MigLayout.)
   :button     #(JButton.)}
  #_{:frame      {:constructor #(JFrame.) :class JFrame}
     :label      {:constructor #(JLabel.) :class JLabel}
     :mig-layout {:constructor #(MigLayout.) :class MigLayout}
     :button     {:constructor #(JButton.) :class JButton}})

(def attr-appliers
  {:background         (fn set-background [c color] (cond
                                                      (instance? JFrame c) (.setBackground (.getContentPane c) (Color. color))
                                                      :else (.setBackground c (Color. color))))
   :opaque             (fn set-opaque [c opaque] (cond
                                                   (instance? JFrame c) (.setOpaque (.getContentPane c) opaque)
                                                   :else (.setOpaque c opaque)))
   :text               (fn set-text [c text] (.setText c text))
   :layout             (fn set-layout [c layout] (.setLayout c (build-ui layout)))
   ; TODO: if action not in on-close-action-map, then add it as a WindowListener to the close event
   :on-close           (fn on-close [c action] (.setDefaultCloseOperation c (on-close-action-map action)))
   :layout-constraints (fn set-layout-constraints [c constraints] (.setLayoutConstraints c constraints))
   ; All action listeners must be removed before adding the new one to avoid re-adding the same anonymous fn.
   :on-action          (fn on-action [c action-handler]
                         (doseq [al (vec (.getActionListeners c))] (.removeActionListener c al))
                         (.addActionListener c (reify-action-listener action-handler)))
   ; TODO: refactor add-contents to a independent defn and check component type to be sure it's a valid container.
   ;  Perhaps pass in the map in addition to the component so that we don't have to use `instanceof`?
   ; TODO:
   ; - specify getter for existing child components
   ; - specify fn for adding new child component at specified index
   ; - no need to specify how to update a child component... that is just as if it was a root component.
   ; - no need to specify how to create a child component... that is also as if it was a root component.
   :contents           {:get-existing-children (fn get-existing-children [c] (.getComponents (.getContentPane c)))
                        :add-new-child-at      (fn add-new-child-at [^Container c ^Component child index] (.add ^Container (.getContentPane c) child ^int index))
                        :remove-child-at       (fn remove-child-at [c index] (.remove (.getContentPane c) index))
                        :get-child-at          (fn get-child-at [c index] (.getComponent (.getContentPane c) index))}
   #_(fn add-contents [c children] (doseq [child children] (.add c (build-ui child))))})

; TODO: use a multimethod to dispatch based on :class
#_(defn add-contents
  [component ui]
  (when (and (= :frame (:class ui))
             (:contents ui))
    (doseq [child (:contents ui)] (.add component (build-ui child))))
  component)

(defn children-applier?
  [attr-applier]
  (and (map? attr-applier)
       (contains? attr-applier :get-existing-children)
       (contains? attr-applier :add-new-child-at)
       (contains? attr-applier :remove-child-at)))

(declare apply-attributes)

(defn pad [col length]
  (vec (take length (concat col (repeat nil)))))

(defn apply-children-applier
  [attr-applier component attr old-view new-view]
  (let [add-new-child-at (:add-new-child-at attr-applier)
        get-child-at (:get-child-at attr-applier)
        old-children (get old-view attr)
        new-children (get new-view attr)
        _ (println "old children:")
        _ (pprint old-children)
        _ (println "new children:")
        _ (pprint new-children)
        max-children (max (count old-children) (count new-children))]
    (doseq [[old-child new-child index]
            (map vector
                 (pad old-children max-children)
                 (pad new-children max-children)
                 (range))]
      #_(println "child component (" index "):" (get-child-at component index))
      (cond
        (nil? old-child) (add-new-child-at component (build-ui new-child) index)

        ; TODO: remove children that aren't in new-children
        ; TODO: check that identity (class name) match before applying attributes, otherwise, remove and add new child
        :else (apply-attributes (get-child-at component index) old-child new-child)
        ))))

(defn apply-attributes
  [component old-view new-view]
  (if (not (= old-view new-view))                           ; short circuit - do nothing if old and new are equal.
    (doseq [attr (set (keys new-view))]
      (if-let [attr-applier (get attr-appliers attr)]
        (cond
          ; TODO: these should not be done within an atom swap! Because they are side effects. Instead, accumulate them
          ; as a list of fns to apply after the swap is complete. In addition, the new view state should be kept with
          ; this list of fns and the atom should be updated with the new view state when a successful update occurs
          ; tic-tok cycle of swaps on the atom...
          (children-applier? attr-applier) (apply-children-applier attr-applier component attr old-view new-view)
          :else (do
                  (println "applying attribute " attr " with value " (get new-view attr))
                  (attr-applier component (get new-view attr)))))))
  component)

(defn instantiate-class
  [ui]
  (let [id (:class ui)
        _ (println "instatiating" id)
        constructor (get-in class-map [(:class ui) #_:constructor])
        component (constructor)]
    (println "component: " component)
    component))

; TODO: perhaps build-ui is more like build-object because :mig-layout is not a UI. And the way things are setup,
; any object can be built with this code.
(defn build-ui
  "Take a ui and realize it."
  [ui]
  (-> (instantiate-class ui)
      (apply-attributes nil ui)))

(defn show-ui
  "Builds and displays a ui. This is useful for top level components in systems that require an explicit show or load
  before anything is displayed."
  [ui]
  (let [window (build-ui ui)]
    (.pack window)
    (.show window)
    window))

(defn my-hello-world-app
  []
  {:constructor
   (fn constructor [props]
     ; return initial app-state. Not necessary if it's an empty map, but I'll put it here.
     {})
   ; TODO: possibly generify componentDidMount to be an event in response to state change. Code may need to execute when
   ; the state changes to pack again or revalidate or any number of other things. And the code may need to execute only
   ; if certain parts of the state changed. Like `pack()` may only execute if visibility goes from false to true. Having
   ; a way to handle this concisely and generically would be amazing!
   :component-did-mount
   (fn component-did-mount [component app-ref app-value]
     (println "made it to component did mount")
     (.pack component)
     (.setVisible component true))
   ; Called when a child is added, removed or "swapped". A swap is when a child is removed and a new one is added in its
   ; place.
   ; TODO: this is not implemented yet.
   :children-changed
   (fn children-changed [component app-ref app-value]
     (.pack component))
   ; TODO: I can create fns to make these maps more expressive. As in:
   ; (frame {:background 0xff0000 :on-close :dispose :layout (mig-layout "flowy)}
   ;        (label (or (:greeting @app-state) "Hello World!"))
   ;        (button "Say Hi!" (fn say-hi [action-event] (println "hello world!") (swap! app-state assoc :greeting "Yo"))))
   :render
   (fn render [app-ref app-value]
     {:class      :frame
      :background (or (get-in app-value [:state :background]) 0xff0000)
      :opaque     true
      :on-close   :dispose
      :layout     {:class              :mig-layout
                   :layout-constraints "flowy"}
      :contents   (let [contents [{:class :label
                                   :text  (or (get-in app-value [:state :greeting]) "Hello World!")}
                                  {:class     :button
                                   :text      "Say Hi!"
                                   ; TODO: this causes an update every time because it generates a new anonymous fn. Think about how to
                                   ; avoid the update every time. A named fn would probably be better, but then how do we get state?
                                   :on-action (fn say-hi [action-event]
                                                (println "hello world!")
                                                (swap! app-ref update-in [:state]
                                                       (fn [state]
                                                         (println "state: " state)
                                                         (if (= "Yo" (:greeting state))
                                                           (assoc state :greeting "Dog" :background 0x4488ff :expanded true)
                                                           (assoc state :greeting "Yo" :background 0x00ffff :expanded true)))
                                                       ))}]]
                    (if (get-in app-value [:state :expanded])
                      (conj contents {:class :label :text "Expanded!"})
                      contents))
      })})

(defn update-view
  [key app-ref old-value new-value]
  (if (and (not (contains? old-value :root-component))
           (contains? new-value :root-component))
    ; Component did mount
    (let [component-did-mount (get-in new-value [:app :component-did-mount])
          component (:root-component new-value)]
      (component-did-mount component app-ref new-value))
    ; Update
    (if (not= (:state old-value) (:state new-value))
      (do
        (println "state changed! updating")
        (let [app (:app new-value)
              view-fn (:render app)
              view (view-fn app-ref new-value)
              old-view (:current-view old-value)]
          (pprint (diff old-view view))
          (if-let [component (:root-component new-value)]
            (do (apply-attributes component old-view view)
                (pprint view)
                (swap! app-ref assoc :current-view view))
            (let [root-component (build-ui view)]
              (swap! app-ref assoc :root-component root-component :current-view view)))

          #_(if (= :frame (:class view))
              (pprint (diff (get-in old-view [:contents]) (get-in view [:contents]))))
          )))))

(defn run-app
  [app]
  (let [constructor (get app :constructor (fn default-constructor [props] {}))
        render (get app :render)
        props {}                                            ; TODO: set props... to what I don't know... maybe this is just a React thing
        app-ref (atom {})
        ; TODO: add watches
        ; TODO: set initial state after adding watches! That way a view-fn call is triggered with initial state! Cool!
        ; TODO: after first watch is triggered, a pack, show must happen on root. How do I do this?? Maybe I don't!
        ;       The user must do that. That could be tricky.
        ;       Perhaps there needs to be a place to just execute code and the user can specify what to execute in
        ;       this place. Kind of like React's componentDidMount and componentWillUnmount functions.
        ; NOTE: root frame needs to be defined before calling view-fn because calling view-fn may trigger watches. Ok,
        ;       so the watches could be "held". This gets back to that concurrency problem. All the code in the view-fn
        ;       must finish before the watches begin. Somehow, we want to pause those watches. Perhaps we should be
        ;       using refs instead of atoms and dosync so that nothing happens on the other refs while we're in these
        ;       refs.
        ; Yeah, so I shouldn't get the view-fn the atom or the ref... I should give it the value of the state. That way
        ; there is no way it can mess up and change the value while running... which could mess up other view-fns.
        ; Especially if I change to dosync. Then I need a special Swing-React fn to update state. That's probably fine.
        ; And that will solve my concurrency problem because I can then control when those updates occur.
        #_root-frame #_(show-ui (view-fn app-ref))]
    (add-watch app-ref :swing-react-update update-view)
    (println "anybody??")
    (let [view (render app-ref @app-ref)
          #_component #_(build-ui view)]
      (swap! app-ref assoc :state (constructor props)       ; domain state
                           :app app)
      ; This will trigger a second call to the view-fn (:render)
      #_(swap! app-ref assoc :root-component component :current-view view)
      (let [app-value @app-ref
            component-did-mount (get-in app-value [:app :component-did-mount] (fn default-component-did-mount [component app-ref app-value]))]
        (println "made it to call of component did mount")
        (component-did-mount (:root-component app-value) app-ref (:state app-value))))
    ))
