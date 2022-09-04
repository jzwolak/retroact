(ns retroact.core
  (:require [clojure.core.async :refer [>! alts!! buffer chan go sliding-buffer]]
            [clojure.tools.logging :as log]
            [retroact.swing :refer [attr-appliers class-map]]))

; Open questions:
;
;  - How to resolve arguments to appliers that need to be objects... like layout managers. And colors for that matter.
;    Currently, I resolve colors by requiring them to be hex values and instantiate the color class with a hex value.
;    But there are many ways to get colors and this is limiting for people used to the full Java library. A resolver
;    for the Color class and the ability to do resolution on arbitrary values (not just components in contents and the
;    argument of build-ui) would be nice.
;    The render fn can generate a Color object in place and that will be passed to the attr-applier. The issue here is
;    that the Color object may not be comparable to a previously instantiated Color object even if they have the same
;    value.
;    Also, since arbitrary components may be present as attributes, the value of the attr may be processed as a
;    component before calling the attr-applier
;    There needs to be a getter and setter for such attrs similar to how children appliers can get, add, and delete
;    child components. Because we need to be able to get the existing onscreen-component in order to apply-attrs to it.
;  - :contents is not actually special. It can be treated as an attribute that takes an array of objects.
;    - attributes can take a primitive value or an object.
;    - in this way, everything becomes orthogonal (normalized).
;  - :class is still different though. It's part of the type or identity. Something that is persistent.
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


(defn component-applier? [attr-applier]
  (and (map? attr-applier)
       (contains? attr-applier :set)
       (contains? attr-applier :get)))


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

(defn- apply-component-applier
  [attr-applier component ctx attr old-view new-view]
  (let [get-sub-comp (:get attr-applier)
        set-sub-comp (:set attr-applier)
        ]
    ))

(defn- apply-children-applier
  [attr-applier component ctx attr old-view new-view]
  (let [add-new-child-at (:add-new-child-at attr-applier)
        get-child-at (:get-child-at attr-applier)
        old-children (get old-view attr)
        new-children (get new-view attr)
        max-children (max (count old-children) (count new-children))]
    (doseq [[old-child new-child index]
            (map vector
                 (pad old-children max-children)
                 (pad new-children max-children)
                 (range))]
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
          (component-applier? attr-applier) (apply-component-applier attr-applier component {:app-ref app-ref} attr old-view new-view)
          ; Assume attr-applier is a fn and call it on the component.
          :else (when (not (= (get old-view attr) (get new-view attr)))
                  (println "applying" attr-applier (get new-view attr))
                  ; TODO: check if attr is a map for a component and apply attributes to it before calling this
                  ; attr-applier, then pass the result to this attr-applier.
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
    component))

; TODO: perhaps build-ui is more like build-object because :mig-layout is not a UI. And the way things are setup,
; any object can be built with this code.
(defn build-ui
  "Take a view and realize it."
  [app-ref view]
  (let [component (instantiate-class view)]
    (apply-attributes {:component component :app-ref app-ref :new-view view})))


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


(defn- trigger-update-view [app-ref new-value]
  (go (>! (get-in new-value [:retroact :update-view-chan]) app-ref)))

; Used for debugging, to count how many times app-watch is called.
(def update-view-count (atom 0))

;TODO: rename to app-watch. There's no need for the "-2" anymore.
(defn app-watch
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
#_(defn print-components [root]
  (when (instance? Container root)
    (doseq [child (.getComponents root)]
      (println child)
      (print-components child))))

; for debugging
#_(defn find-component
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
     (add-watch app-ref :retroact-watch app-watch)
     app-ref))
  ([comp] (init-app comp {}))
  ([comp props]
   (let [app-ref (init-app)]
     (create-comp app-ref comp props)
     app-ref))
  )
