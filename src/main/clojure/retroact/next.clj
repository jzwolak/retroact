(ns retroact.next
  (:require [clojure.core.async :refer [>! alts!! buffer chan go sliding-buffer]]
            [clojure.set :refer [difference]]
            [clojure.tools.logging :as log]
            [retroact.swing :refer [redraw-onscreen-component]]
            [retroact.swing.util :refer [assoc-view]])
  (:import (clojure.lang Agent Atom Ref)
           (java.beans Introspector)))

; An app has app state stored in some kind of ref type: atom, agent, or ref. Metadata is associated with this for
; Retroact to track the relation between the app state and onscreen resources. Retroact also has its own internal
; state to manage onscreen resources and efficiently update them.


; The keys fall into categories. I can separate them and group them into sub keys or just flatten them and sort them
; out while iterating over the map.
;
; :component represents the type of the component which is part of the object's identity and is going to be persistent.
; :background is an attribute that may change.
; :contents point to children objects that have their own identities and attributes (and possibly children). They make
;           this a tree.
;
; I can put all the attributes in a key called :attributes or :attrs or something.
; Later there may also be other metadata keys for things like an id of the component in the case of lists of things.
; (think React's need for assigning ids to know if something was added, removed, or moved in a list since everything
; else about it may change).
;
; For now, I'll stick with the flat map and see how it goes.


; TODO: destroy or remove components should remove side-effect fn from app-ref meta and remove component from main
; retroact loop.

(declare build-ui)
(declare retroact-main)

; I believe this is just used for debugging and convenience.
; Deprecated, use state
(defonce app-refs (atom []))

(defonce state (atom {:apps {}}))

(defonce retroact-cmd-chan (delay (retroact-main)))

(defn get-app-id [app-ref] (:retroact-app-id (meta app-ref)))

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

(defn- should-build-sub-comp? [old-sub-comp new-sub-comp]
  (or
    (and (nil? old-sub-comp) (not (nil? new-sub-comp)))     ; subcomponent is new
    (and (not (nil? old-sub-comp))
         (not (nil? new-sub-comp))
         (not= (:class old-sub-comp) (:class new-sub-comp))) ; subcomponent class changed
    ))

(defn- should-update-sub-comp? [old-sub-comp new-sub-comp]
  ; both not nil and class same and not equal
  (and (not= old-sub-comp new-sub-comp)
       (not (nil? old-sub-comp)) (not (nil? new-sub-comp))
       (= (:class old-sub-comp) (:class new-sub-comp))))

(defn- should-remove-sub-comp? [old-sub-comp new-sub-comp]
  (and (not (nil? old-sub-comp))
       (nil? new-sub-comp)))

(defn- apply-component-applier
  [attr-applier component ctx attr old-view new-view]
  (let [get-sub-comp (:get attr-applier)
        set-sub-comp (:set attr-applier)
        old-sub-comp (get old-view attr)
        new-sub-comp (get new-view attr)]
    (cond
      (should-build-sub-comp? old-sub-comp new-sub-comp) (set-sub-comp component ctx (build-ui (:app-ref ctx) new-sub-comp))
      (should-update-sub-comp? old-sub-comp new-sub-comp) (apply-attributes {:onscreen-component (get-sub-comp component) :app-ref (:app-ref ctx) :old-view old-sub-comp :new-view new-sub-comp})
      (should-remove-sub-comp? old-sub-comp new-sub-comp) (set-sub-comp component ctx nil)
      :default (do)                                         ; no-op. Happens if both are nil or they are equal
      )
    ))

(defn- replace-child-at
  [app-ref remove-child-at add-new-child-at component new-child index]
  (remove-child-at component index)
  (add-new-child-at component (build-ui app-ref new-child) new-child index))



(defn get-bean-property-descriptor [object prop-name]
  (log/info "getting bean property for " prop-name)
  (let [prop-name (if (keyword? prop-name) (subs (str prop-name) 1) prop-name)
        klass (.getClass object)
        pds (-> (Introspector/getBeanInfo klass)
               (.getPropertyDescriptors))]
    (first (filter #(= prop-name (.getName %)) pds))))

(declare instantiate-class)

(defn- get-or-instantiate [onscreen-component prop-name old-view new-view]
  (let [old-constructor-args (:constructor-args old-view)
        new-constructor-args (:constructor-args new-view)
        pd (get-bean-property-descriptor onscreen-component prop-name)
        getter (.getReadMethod pd)
        prop-val (.invoke getter onscreen-component (into-array Object []))]
    (if (and (= old-constructor-args new-constructor-args)
             (.isInstance (:class new-view) prop-val))
      prop-val
      (let [new-instance (instantiate-class new-view)
            setter (.getWriteMethod pd)]
        (.invoke setter onscreen-component (into-array Object [new-instance]))
        new-instance))))

(defn update-children
  [onscreen-component app-ref name old-children new-children]
  ; TODO: if bean has an indexed property, consider using the indexed property to modify children instead of looking
  ; for multimethod in toolkit bindings.
  (let [app-id (get-app-id app-ref)
        toolkit-bindings (get-in @state [:apps app-id :toolkit-bindings])
        pd (get-bean-property-descriptor onscreen-component name)
        klass (.getComponentType (.getPropertyType pd))
        add-child-at (get-in toolkit-bindings [:defaults :add-child-at]
                          (fn add-child-using-reflection [container child index]
                            (let [method (.getMethod (.getClass onscreen-component) "add" (into-array Class [klass Integer/TYPE]))]
                              (.invoke method container (into-array Object [child index])))))
        get-child-at (get-in toolkit-bindings [:defaults :get-child-at])
        remove-child-at (get-in toolkit-bindings [:defaults :remove-child-at])
        max-children (count new-children)]
    (doseq [[old-child new-child index]
            (map vector
                 (pad old-children max-children)
                 (pad new-children max-children)
                 (range))]
      (log/info "child index" index "| old-child =" old-child "log msg1")
      (log/info "child index" index "| new-child =" new-child "log msg2")
      (cond
        (nil? old-child) (add-child-at onscreen-component (build-ui app-ref new-child) new-child index)
        (not= (:class old-child) (:class new-child)) (replace-child-at app-ref remove-child-at add-child-at onscreen-component new-child index)
        (and old-child new-child) (apply-attributes {:onscreen-component (get-child-at onscreen-component index) :app-ref app-ref :old-view old-child :new-view new-child})
        ))
    (when (> (count old-children) (count new-children))
      (doseq [index (range (dec (count old-children)) (dec (count new-children)) -1)]
        (remove-child-at onscreen-component index)))
    (redraw-onscreen-component onscreen-component)
    #_(log/info "got pd =" pd)
    #_(log/info "add-method for" name "=" add-child)
    #_(doseq [child-view new-val]
      (let [onscreen-child-component (instantiate-class child-view)]
        (apply-attributes {:onscreen-component onscreen-child-component
                           :app-ref            app-ref
                           :old-view           nil
                           :new-view           child-view})
        (add-child onscreen-component onscreen-child-component)))))

(defn- update-properties
  [onscreen-component app-ref old-view new-view]
  (let [old-props (:properties old-view)
        new-props (:properties new-view)]
    (when (not= old-props new-props)
      ; TODO: do something about props removed from old to new
      (doseq [name (set (keys new-props))]
        (let [new-val (get new-props name)
              old-val (get old-props name)]
          (log/info name "=" new-val)
          (cond
            (vector? new-val) (update-children onscreen-component app-ref name old-val new-val)
            (map? new-val) (apply-attributes {:onscreen-component (get-or-instantiate onscreen-component name old-val new-val)
                                              :app-ref            app-ref
                                              :old-view           old-val
                                              :new-view           new-val})
            :else (when (not (= old-val new-val))
                    (log/info "applying attribute" name " = " (get new-props name))
                    (if-let [pd (get-bean-property-descriptor onscreen-component name)]
                      (if-let [setter (.getWriteMethod pd)]
                        (.invoke setter onscreen-component (into-array Object [new-val]))
                        (log/error "setter is nil, skipping property" name))
                      (log/error "could not find property descriptor for" name "on"
                                 (.getName (.getClass onscreen-component)) "skipping update/set for property")))))))))

(defn- update-listeners
  [onscreen-component app-ref old-view new-view]
  (let [old-listeners (:listeners old-view)
        new-listeners (:listeners new-view)]
    (when (not= old-listeners new-listeners)
      )))

(defn apply-attributes
  [{:keys [onscreen-component app-ref old-view new-view]}]
  (log/info "applying attributes" (:class new-view) "log msg3")
  (when (not (= old-view new-view))                           ; short circuit - do nothing if old and new are equal.
    (update-properties onscreen-component app-ref old-view new-view)
    (update-listeners onscreen-component app-ref old-view new-view)
    (assoc-view onscreen-component new-view))
  onscreen-component)

(defn instantiate-class
  [view]
  (let [klass (:class view)
        constructor-args-raw (get view :constructor-args [])
        _ (log/info "building view for" klass "using no-arg construct. log msg4")
        _ (log/info "maybe not no-arg constructor")
        _ (log/info "raw args:" constructor-args-raw)
        constructor-args (mapv (fn [ca] (if (vector? ca) (second ca) ca)) constructor-args-raw)
        constructor-arg-types (mapv (fn [ca] (if (vector? ca) (first ca) (type ca))) constructor-args-raw)
        _ (log/info "args:" constructor-args)
        _ (log/info "types:" constructor-arg-types)
        component (.newInstance (.getConstructor klass (into-array Class constructor-arg-types))
                                (into-array Object constructor-args))]
    (log/info "created" klass)
    component))

; TODO: perhaps build-ui is more like build-object because :mig-layout is not a UI. And the way things are setup,
; any object can be built with this code.
(defn build-ui
  "Take a view and realize it."
  [app-ref view]
  (let [onscreen-component (instantiate-class view)]
    (apply-attributes {:onscreen-component onscreen-component :app-ref app-ref :new-view view})))


(defn component-did-mount? [old-value new-value]
  (let [old-components (get old-value :components {})
        new-components (get new-value :components {})]
    (if (not= old-components new-components)
      (filter (fn is-component-mounted? [comp]
                (let [comp-id (:comp-id comp)
                      old-onscreen-comp (get-in old-components [comp-id :onscreen-component])
                      onscreen-comp (get comp :onscreen-component)]
                  (and (nil? old-onscreen-comp) (not (nil? onscreen-comp)))))
              (vals new-components)))))


(defn- component-added?
  [old-value new-value]
  (let [old-comps (:components old-value)
        new-comps (:components new-value)]
    (when (not= old-comps new-comps)
      (let [new-comp-ids (difference (set (keys new-comps)) (set (keys old-comps)))]
        (not-empty new-comp-ids)))))


; This fn has potential to be elevated to public in a different namespace. Its usefulness is greater than this limited
; use here in Retroact.
(defn- call-with-catch
  [fn & args]
  (try
    (apply fn args)
    (catch Exception ex
      (let [current-thread (Thread/currentThread)
            uncaught-ex-handler (.getUncaughtExceptionHandler current-thread)]
        (.uncaughtException uncaught-ex-handler current-thread ex)))))


(defn- call-side-effects [app-ref old-value new-value]
  (doseq [[_ side-effect] (get-in (meta app-ref) [:retroact :side-effects])]
    (call-with-catch side-effect app-ref old-value new-value)))

(defn- run-cmd
  [app-ref cmd]
  (let [app-id (get-app-id app-ref)]
    (log/info "got app-id for run-cmd" app-id)
    (go (>! (get-in @state [:apps app-id :update-view-chan]) cmd))))

(defn- trigger-update-view [app-ref]
  (run-cmd app-ref [:update-view app-ref]))

(defn app-watch
  [watch-key app-ref old-value new-value]
  ; Update view when state changed
  (when (not= old-value new-value)
    (call-side-effects app-ref old-value new-value)
    (trigger-update-view app-ref)))

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
  (let [onscreen-component (or onscreen-component (instantiate-class new-view))]
    (apply-attributes {:app-ref app-ref :onscreen-component onscreen-component :old-view old-view :new-view new-view})
    onscreen-component))

(defn- get-render-fn [comp]
  (get comp :render (fn default-render-fn [app-ref app-value]
                      (log/warn "component did not provide a render fn"))))

(defn- update-component
  "Update onscreen-component, create it if necessary, and return the new view and updated onscreen-component."
  [app-ref app comp]
  (let [render (get-render-fn comp)
        view (:view comp)
        onscreen-component (:onscreen-component comp)
        update-onscreen-component (or (:update-onscreen-component comp) update-onscreen-component)
        new-view (render app-ref app)
        updated-onscreen-component
        (if (or (not onscreen-component) (not= view new-view))
          (update-onscreen-component
            {:app-ref  app-ref :onscreen-component onscreen-component
             :old-view view :new-view new-view})
          onscreen-component)]
    (when (and (nil? onscreen-component) (not (nil? updated-onscreen-component)))
      (let [component-did-mount (get comp :component-did-mount (fn default-component-did-mount [comp app-ref new-value]))]
        (component-did-mount updated-onscreen-component app-ref app)))
    (assoc comp :view new-view :onscreen-component updated-onscreen-component)))

(defn- update-components [app-ref app components]
  ; app has a value for components, but components contains the component data matching the onscreen-components. So we
  ; use it.
  (reduce-kv
    (fn render-onscreen-comp [m comp-id comp]
      #_(log/info "update-components comp =" comp "log msg6") ; This can be a lot of data.
      ; TODO: change this to update-component instead of update-onscreen-component and have it update the new-view, too.
      ; The update to the new-view will modify the result of the render fn with substitutions for objects.
      ; So I did add update-component, but now I need to go into that fn and update new-view... but that isn't exactly
      ; what I initially intended. I wanted to change the name of update-onscreen-component and have it update new-view,
      ; too.
      (assoc m comp-id (update-component app-ref app comp)))
    ; This is the only reference to components in app. All other references to components is the local components to
    ; the retroact-main-loop. It's here because there may be multiple apps running and the main loop services them all.
    ; When the app state changes, only the components for that app are to be updated. I can make this so by storing the
    ; relationship between apps and components in the main loop instead of inside the app state. Components may be added
    ; or removed, and they are added to app... this is why the app must be used. There is already a map from chan to
    ; components. If added components go there then there's no need for using app here.
    ; To do that, I can just add another command: :add-component
    {} components))

(defn- retroact-main-loop [retroact-cmd-chan]
  (log/trace "STARTING RETROACT MAIN LOOP")
  (loop [chans [retroact-cmd-chan]
         app-ref->components {}]
    ; There's one chan for each app-ref so that multiple state changes can be coalesced to a single update here.
    (let [[val port] (alts!! chans :priority true)
          cmd-name (first val)]
      (condp = cmd-name
        ; TODO: the following eventually makes calls to Swing code which is not thread safe. This thread is not the EDT.
        ; Therefore, do something to make sure those calls get to the EDT and make sure it's done in a way independent
        ; of the mechanics of Swing - i.e., so that it will work with other toolkits, like JavaFX, SWT, etc..
        :update-view (let [app-ref (second val)
                           app @app-ref
                           components (get app-ref->components app-ref {})
                           next-components (update-components app-ref app components)
                           next-app-ref->components (assoc app-ref->components app-ref next-components)]
                       ; - recur with update-view-chans that has update-view-chan at end so that we can guarantee earlier chans get read. Check alt!!
                       ;   to be sure priority is set - I remember seeing that somewhere.
                       ; - loop through all components in current app-ref.
                       ; update-view-chan has app-ref for the app at the other end of it. There is one update-view-chan per app.
                       ;
                       ; No worries about map running outside swap!. The :view inside :components vector is only updated in this
                       ; place and this place is sequential. In addition, changes to :state will always trigger another put to
                       ; update-view-chan, which will cause this code to run again. In the worst case the code runs an extra time,
                       ; not too few times.
                       ; TODO: update docs. In fact, running the map outside swap! is critical. Because I want to update view and
                       ; the Swing components together. Once the two are updated I can call swap! and pass it the correct value of
                       ; view and swap! will just rerun until it is set. The only problem I see here is that the user will have time
                       ; to interact with the onscreen components before swap! finishes. But that really shouldn't be a problem
                       ; because the only code that would be affected by such user actions would be the code right here and since this
                       ; code is blocking until the swap! completes... there's no problem.
                       ; TODO: move update-view-chan to end of update-view-chans so that there are no denial of service issues.
                       (recur chans next-app-ref->components))
        :update-view-chan (let [update-view-chan (second val)] (recur (conj chans update-view-chan) app-ref->components))
        :add-component (let [{:keys [component app-ref app]} (second val)
                             components (get app-ref->components app-ref {})
                             comp-id (:comp-id component)
                             next-component (update-component app-ref app component)
                             next-components (assoc components comp-id next-component)]
                         (recur chans (assoc app-ref->components app-ref next-components)))
        :remove-component (let [{:keys [comp-id app-ref]} (second val)
                                next-components (dissoc (get app-ref->components app-ref) comp-id)
                                next-app-ref->components (assoc app-ref->components app-ref next-components)]
                            (recur chans next-app-ref->components))
        :shutdown (do)                                      ; do nothing, will not recur
        (do (log/error "unrecognized command to retroact-cmd-chan, ignoring:" cmd-name)
            (recur chans app-ref->components)))
      )))

(defn- retroact-main []
  (let [retroact-cmd-chan (chan (buffer 100))
        retroact-thread (Thread. (fn retroact-main-runnable [] (retroact-main-loop retroact-cmd-chan)))]
    (.start retroact-thread)
    retroact-cmd-chan))


(defn- alter-app-ref! [app-ref f]
  "Alters value of app-ref by applying f to the current value. Works on ref/atom/agent types and returns a valid value
  of the ref at some point. For atoms and refs the value is guaranteed to be the value immediately following the alter.
  For agents the value is non-deterministic but will be a valid value at some point in the agent's history."
  (condp instance? app-ref
    Atom (swap! app-ref f)
    Ref (dosync (alter app-ref f) @app-ref)
    Agent (do (send app-ref f) @app-ref)))

(defn- check-side-effect-structure
  [side-effect]
  (cond
    ; The perfect example of a side effect
    (and (map? side-effect))
    side-effect
    ; A lone fn implementing a side effect, generate a UUID for the fn.
    (fn? side-effect)
    (do
      (log/warn "side effect without a key, consider using the {:key-id (fn side-effect [app-ref old-val new-val] ...)} form")
      {(keyword (gensym "retroact-side-effect-")) side-effect})
    :default
    (do
      (log/error "side effect is not a map or a fn. Please check the code and be sure the side effect appears correctly")
      {})))

(defn- register-side-effect
  [app-val side-effect]
  (let [side-effects-to-add (filter (comp fn? last) side-effect)]
    (update-in app-val [:retroact :side-effects] merge side-effects-to-add)))

(defn destroy-comp
  "Destroy a component. You'll need to keep comp-id created with create-comp in order to call this."
  [app-ref comp-id]
  (run-cmd app-ref [:remove-component {:app-ref app-ref :comp-id comp-id}]))

(defn create-comp
  "Create a new top level component. There should not be many of these. This is akin to a main window. In the most
   extreme cases there may be a couple of hundred of these. In a typical case there will be between one component and a
   half a dozen components. The code is optimized for a small number of top level components. Legacy apps that wish to
   mount components in an existing non-Retroact component can use this to construct such \"detached\" components, which
   are essentially top level components as far as Retroact is concerned but not in the native windowing system. Note,
   the onscreen-component is built asynchronously and may be added to the legacy component in its component-did-mount
   or ... TODO: in the future there may be another way to do this."
  ([app-ref comp props]
   (let [constructor (get comp :constructor (fn default-constructor [props state] state))
         comp-id (keyword (gensym "comp"))
         ; Add a unique id to ensure component map is unique. Side effects by duplicate components should
         ; generate duplicate onscreen components and we need to be sure the data here is unique. Onscreen
         ; components store Java reference in comp, but it won't be here immediately.
         comp (assoc comp :comp-id comp-id)]
     ; No need to render comp view here because this will trigger the watch, which will render the view.
     (let [app
           (alter-app-ref! app-ref
                           (fn add-component-to-app [app]
                             (constructor props app)))]
       (when-let [side-effect (:side-effect comp)]
         (log/info "registering side effect for" comp "log msg7")
         (alter-meta! app-ref register-side-effect (check-side-effect-structure side-effect)))
       (run-cmd app-ref [:add-component {:app-ref app-ref :app app :component comp}])
       comp-id)))
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
; - rerun view render fns for each component whose state has changed.
;   - what happens if a parent's state changes and the parent reruns a child with different props and the child's state
;     had also changed the same time as the parent's state. What prevents the child from re-rendering first then the
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
   ; This zero arg version of init-app should be removed. It's just a wrapper to init-app-ref. Call init-app-ref
   ; directly. Create a zero arg version of init-app-ref.
   #_(init-app-ref (atom {})))
  ([comp] (init-app comp {}))
  ([comp props]
   (let [app-ref (init-app)]
     (create-comp app-ref comp props)
     app-ref)))


; What do I really want to call this fn?
; The goal is to create something that can be used to guide future calls to retroact fns to shape their behavior. The
; result of this fn call should be just a map or something. Not some obfuscated object, though the contents will be
; unintelligible to the user.
; Here are some options:
; client
; init
; retroact
; context
; toolkit
; interface
; ui
; laf (for look-and-feel)
; look-and-feel
; configuration
; conf
; config
;
; Okay, so init-app and init-app-ref from the original Retroact design or complected and do parts of what I want.
; Decomplect them. Simplify them. One is initializing the app ref. This one can also add the toolkit bindings and look
; and feel to the app ref. It should, more properly, be called init-app. But I should rename it all together to avoid
; confusion. Then the current init-app fn is actually shorthand for creating a component and initializing the app.
; Perhaps just don't do that shorthand.
;
; What is the problem I'm trying to solve with the state ref and local state in the retroact-main-loop? I need state for
; Retroact, that's part of the problem. I need state for each app-ref, which there should really only be one anyway.
; The state is of no concern to the developer, so I don't want to store it in the app-ref. I want the developer to have
; an easy time using Retroact without having to pass around this state - that's a criteria, not the problem.
; The state may grow over time. I want the fn calls to be reproducible when the app state is the same. I know onscreen
; may be different, and that's ok.
(defn create
  "Creates a ref (default type atom of a map) to what can be used as the application state. Retroact needs to track some
  state and puts some state in the map under the :retroact key. Optionally, a toolkit and look and feel may be
  specified. The look and feel (laf) is a Retroact thing and not the toolkits laf. Retroact has a way of modifying the
  toolkit's laf from the developer's point of view.
  old docs: Takes toolkit bindings and returns a Retroact handle to be used for initializing applications. The default
  toolkit bindings are legacy swing and should not be used. They exist for backward compatibility. Specify a toolkit
  using its get-<toolkit-name>-bindings fn. config is {:toolkit-bindings tb :look-and-feel laf} where both are optional
  and the empty map is acceptable."
  ([] (create (atom {})))
  ([app-ref] (create app-ref {}))
  ([app-ref opts]
   (let [app-id (str (gensym "R"))
         update-view-chan (chan (sliding-buffer 1))]
     (swap! state assoc-in [:apps app-id] {:update-view-chan update-view-chan
                                           :toolkit-bindings (:toolkit-bindings opts)
                                           :app-ref          app-ref})
     (alter-meta! app-ref assoc :retroact-app-id app-id)
     (go (>! @retroact-cmd-chan [:update-view-chan update-view-chan]))
     (swap! app-refs conj app-ref)
     ; no need to start thread here... it automatically starts when the retroact-cmd-chan is used.
     (add-watch app-ref :retroact-watch app-watch)
     #_(binding [*print-meta* true]
         (prn app-ref))
     app-ref)))
