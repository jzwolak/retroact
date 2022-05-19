(ns swing-react.core
  (:require [clojure.data :refer [diff]]
            [clojure.pprint :refer [pprint]])
  (:import (java.awt Color Button Container Component)
           (javax.swing JFrame JLabel JButton)
           (net.miginfocom.swing MigLayout)
           (java.awt.event ActionListener)))

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
  {:background         (fn set-background [c ctx color] (cond
                                                          (instance? JFrame c) (.setBackground (.getContentPane c) (Color. color))
                                                          :else (.setBackground c (Color. color))))
   :opaque             (fn set-opaque [c ctx opaque] (cond
                                                       (instance? JFrame c) (.setOpaque (.getContentPane c) opaque)
                                                       :else (.setOpaque c opaque)))
   :text               (fn set-text [c ctx text] (.setText c text))
   :layout             (fn set-layout [c ctx layout] (.setLayout c (build-ui (:app-ref ctx) layout)))
   ; TODO: if action not in on-close-action-map, then add it as a WindowListener to the close event
   :on-close           (fn on-close [c ctx action] (.setDefaultCloseOperation c (on-close-action-map action)))
   :layout-constraints (fn set-layout-constraints [c ctx constraints] (.setLayoutConstraints c constraints))
   ; All action listeners must be removed before adding the new one to avoid re-adding the same anonymous fn.
   :on-action          (fn on-action [c ctx action-handler]
                         (doseq [al (vec (.getActionListeners c))] (.removeActionListener c al))
                         (.addActionListener c (reify-action-listener (fn action-handler-clojure [action-event]
                                                                        (action-handler (:app-ref ctx) action-event)))))
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
        (nil? old-child) (add-new-child-at component (build-ui (:app-ref ctx) new-child) index)

        ; TODO: remove children that aren't in new-children
        ; TODO: check that identity (class name) match before applying attributes, otherwise, remove and add new child
        :else (apply-attributes {:component (get-child-at component index) :app-ref (:app-ref ctx) :old-view old-child :new-view new-child})
        ))))

(defn apply-attributes
  [{:keys [component app-ref old-view new-view]}]
  (if (not (= old-view new-view))                           ; short circuit - do nothing if old and new are equal.
    (doseq [attr (set (keys new-view))]
      (if-let [attr-applier (get attr-appliers attr)]
        (cond
          ; TODO: these should not be done within an atom swap! Because they are side effects. Instead, accumulate them
          ; as a list of fns to apply after the swap is complete. In addition, the new view state should be kept with
          ; this list of fns and the atom should be updated with the new view state when a successful update occurs
          ; tic-tok cycle of swaps on the atom...
          ; TODO: mutual recursion here could cause stack overflow for deeply nested UIs? Will a UI every be that deeply
          ; nested?? Still... I could use trampoline and make this the last statement. Though trampoline may not help
          ; since apply-children-applier iterates over a sequence and calls apply-attributes. That iteration would have
          ; to be moved to apply-attributes or recursive itself.
          (children-applier? attr-applier) (apply-children-applier attr-applier component {:app-ref app-ref} attr old-view new-view)
          ; Assume attr-applier is a fn and call it on the component.
          :else (do
                  #_(println "applying attribute " attr " with value " (get new-view attr))
                  (attr-applier component {:app-ref app-ref} (get new-view attr)))))))
  component)

(defn instantiate-class
  [ui]
  (let [id (:class ui)
        ;_ (println "instatiating" id)
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

(defn update-view
  [watch-key app-ref old-value new-value]
  ; TODO: "mount" is not an appropriate term, I took this from React. "create" would be better. Think about it.
  ; Component did mount
  (when (and (not (contains? old-value :root-component))
             (contains? new-value :root-component))
    (println "LIFECYCLE: root component mounted.")
    (let [component-did-mount (get-in new-value [:app :component-did-mount])
          component (:root-component new-value)]
      (component-did-mount component app-ref new-value)))
  ; Update
  (when (not= (:state old-value) (:state new-value))
    (println "LIFECYCLE: state changed. updating.")
    (let [app (:app new-value)
          render (:render app)
          old-view (:current-view old-value)
          new-view (render app-ref new-value)]
      (pprint (diff old-view new-view))
      (if-let [root-component (:root-component new-value)]
        ; root component exists, update it
        (do (apply-attributes {:app-ref app-ref :component root-component :old-view old-view :new-view new-view})
            #_(pprint new-view)
            (swap! app-ref assoc :current-view new-view))
        ; root component does not exist, create it and associate it ("mount" it)
        (let [root-component (build-ui app-ref new-view)]
          (swap! app-ref assoc :root-component root-component :current-view new-view))))))

(defn run-app
  [app]
  (let [constructor (get app :constructor (fn default-constructor [props] {}))
        props {}                                            ; TODO: set props... to what I don't know... maybe this is just a React thing
        app-ref (atom {})]
    (add-watch app-ref :swing-react-update update-view)
    (swap! app-ref assoc :state (constructor props)         ; domain state
           :app app)
    (let [app-value @app-ref
          component-did-mount (get-in app-value [:app :component-did-mount] (fn default-component-did-mount [component app-ref app-value]))]
      (component-did-mount (:root-component app-value) app-ref app-value))
    ))
