(ns retroact.core
  (:require [clojure.core.async :refer [>! >!! <!! alts!! buffer chan go sliding-buffer]]
            [clojure.set :refer [difference union]]
            [clojure.tools.logging :as log]
            [clojure.data :refer [diff]]
            [retroact.algorithms.core :as ra]
            [retroact.swing :as swing])
  (:import (clojure.lang Agent Atom Ref)
           (java.lang.ref WeakReference)))

; TODO: add ability to register exception handler. Wrap all calls to component-did-mount and component-did-update with
; try-catch and call registered exception handlers. Wrap outer loop of retroact main loop in try catch to add a fail
; safe. Call registered exception handlers if it fails with extra warning about possible problem in Retroact, but at
; least the main loop will continue.

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
; Later there may also be other metadata keys for things like an id of the component in the case of lists of things.
; (think React's need for assigning ids to know if something was added, removed, or moved in a list since everything
; else about it may change).
;
; For now, I'll stick with the flat map and see how it goes.


; TODO: destroy or remove components should remove side-effect fn from app-ref meta and remove component from main
; retroact loop.

; TODO: remove this state and use only the state within the Retroact main loop. Use queries to asynchronously get state
; from the main loop.
(defonce retroact-state (atom {:app-refs []
                               :id->comp {}}))

(declare build-ui)
(declare update-component)
(declare retroact-cmd-chan)

(declare retroact-attr-appliers)

(defonce retroact-thread-id (atom -1))

(defn gen-retroact-thread-id []
  (swap! retroact-thread-id inc))

(defn- capture-stack [f]
  (let [prison (Exception. "prison exception, should never be thrown")]
    (fn [& args]
      (try
        (apply f args)
        (catch Exception cause
          (let [rex (RuntimeException. "captured stack" cause)]
            (.setStackTrace rex (.getStackTrace prison))
            (throw rex)))))))

(defn get-in-toolkit-config [ctx k]
  @(resolve (get-in ctx [:app-val :retroact :toolkit-config k])))

(defn run-on-toolkit-thread [ctx f & args]
  (let [tk-run-on-toolkit-thread (get-in-toolkit-config ctx :run-on-toolkit-thread)
        f-in-stack-prison (capture-stack f)]
    (apply tk-run-on-toolkit-thread f-in-stack-prison args)))

(defn run-on-toolkit-thread-with-result [ctx f & args]
  (let [result-chan (chan 1)]
    (run-on-toolkit-thread ctx #(>!! result-chan (let [result (apply f args)] (if (nil? result) ::nil result))))
    (let [result (<!! result-chan)]
      (if (not= ::nil result) result nil))))

(defn redraw-onscreen-component [ctx component]
  (let [tk-redraw-onscreen-component (get-in-toolkit-config ctx :redraw-onscreen-component)]
    (run-on-toolkit-thread ctx tk-redraw-onscreen-component component)))

(defn assoc-view [ctx onscreen-component view]
  (let [tk-assoc-view (get-in-toolkit-config ctx :assoc-view)]
    (run-on-toolkit-thread ctx tk-assoc-view onscreen-component view)))

(defn get-view [ctx onscreen-component]
  (let [tk-get-view (get-in-toolkit-config ctx :get-view)]
    (run-on-toolkit-thread-with-result ctx tk-get-view onscreen-component)))


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
        old-sub-view (get old-view attr)
        new-sub-view (get new-view attr)]
    #_(log/info "applying component applier for " attr " on " component)
    (cond
      (should-build-sub-comp? old-sub-view new-sub-view)
        (let [sub-component (build-ui ctx new-sub-view)]
          (run-on-toolkit-thread ctx set-sub-comp component ctx sub-component))
      (should-update-sub-comp? old-sub-view new-sub-view)
        (apply-attributes (assoc ctx :onscreen-component (run-on-toolkit-thread-with-result ctx get-sub-comp component ctx)
                                     :old-view old-sub-view :new-view new-sub-view))
      (should-remove-sub-comp? old-sub-view new-sub-view) (run-on-toolkit-thread ctx set-sub-comp component ctx nil)
      :default (do)                                         ; no-op. Happens if both are nil or they are equal
      )))

(defn- replace-child-at
  [ctx remove-child-at add-new-child-at component new-child index]
  (run-on-toolkit-thread ctx remove-child-at component index)
  (run-on-toolkit-thread ctx add-new-child-at component (build-ui ctx new-child) new-child index))

(defn- get-children [view attr]
  (vec (remove nil? (get view attr))))

(defn- apply-children-applier-fallback
  [attr-applier component ctx attr old-view new-view]
  (log/warn "using fallback children applier. Try setting :id on all children for better performance and robustness.")
  (let [add-new-child-at (:add-new-child-at attr-applier)
        get-child-at (:get-child-at attr-applier)
        remove-child-at (:remove-child-at attr-applier)
        old-children (get-children old-view attr)
        new-children (get-children new-view attr)
        max-children (count new-children)]
    ; TODO: consider forcing new-children to be a vec here.
    (doseq [[old-child new-child index]
            (map vector
                 (pad old-children max-children)
                 (pad new-children max-children)
                 (range))]
      ; The following two can produce tons of log output because the children may be large maps.
      #_(log/info "child index" index "| old-child =" old-child "log msg1")
      #_(log/info "child index" index "| new-child =" new-child "log msg2")
      (cond
        (nil? old-child) (do
                           (log/info "adding child at" index "for component" component)
                           (run-on-toolkit-thread ctx add-new-child-at component (build-ui ctx new-child) new-child index))
        (not= (:class old-child) (:class new-child)) (do
                                                       (log/info "replacing child at" index "for component" component)
                                                       (replace-child-at ctx remove-child-at add-new-child-at component new-child index))
        (and old-child new-child) (do
                                    (log/info "updating child at" index "for component" component)
                                    (apply-attributes
                                        (assoc ctx
                                          :onscreen-component (run-on-toolkit-thread-with-result ctx get-child-at component index)
                                          :old-view old-child :new-view new-child)))))
    (when (> (count old-children) (count new-children))
      (doseq [index (range (dec (count old-children)) (dec (count new-children)) -1)]
        (log/info "removing child at" index "for" (:class new-view))
        (run-on-toolkit-thread ctx remove-child-at component index)))
    (redraw-onscreen-component ctx component)))

(defn- create-child-id->index [view attr]
  (into {} (map (fn [child index] [(:id child) index]) (get view attr) (range))))

(defn- apply-children-applier-with-ids
  [attr-applier component ctx attr old-view new-view]
  (let [{:keys [remove-child-at get-child-at add-new-child-at]} attr-applier
        old-children (get-children old-view attr)
        new-children (get-children new-view attr)
        old-id->child (into {} (map (fn [child] [(:id child) child]) old-children))
        new-id->child (into {} (map (fn [child] [(:id child) child]) new-children))
        old-child-ids (mapv :id old-children)
        new-child-ids (mapv :id new-children)
        [remove-ops insert-ops] (ra/calculate-patch-operations old-child-ids new-child-ids)
        id->component
        (loop [id->component {} remove-op (first remove-ops) remove-ops (rest remove-ops)]
          (cond
            (nil? remove-op) id->component
            :else (let [[_ index id] remove-op
                        child-component (run-on-toolkit-thread-with-result ctx get-child-at component index)]
                    (run-on-toolkit-thread ctx remove-child-at component index)
                    (recur (assoc id->component id child-component) (first remove-ops) (rest remove-ops)))))]
    ; insert, create if necessary
    (doseq [[_ index id] insert-ops]
      (let [child-view (get new-id->child id)
            child-component (get id->component id (build-ui ctx child-view))]
        (run-on-toolkit-thread ctx add-new-child-at component child-component child-view index)))
    ; loop through all new children in view (not onscreen-component) and if there's a matching old child, then run update
    (doseq [[{:keys [id] :as child} index] (map vector new-children (range))]
      (when-let [old-child-view (get old-id->child id)]
        (apply-attributes (assoc ctx
                            :onscreen-component (run-on-toolkit-thread-with-result ctx get-child-at component index)
                            :old-view old-child-view :new-view child))))
    )
  #_(let [old-child-id->index (create-child-id->index old-view attr)
        new-child-id->index (create-child-id->index new-view attr)
        conserved-child-ids (union (set (keys new-child-id->index)) (set (keys old-child-id->index)))
        first-old-child-id (first (apply min-key second (select-keys old-child-id->index conserved-child-ids)))
        first-new-child-id (first (apply min-key second (select-keys new-child-id->index conserved-child-ids)))
        ]
    ))

(defn- apply-children-applier
  "If all children in old-view and new-view have an :id then use those :id's to determine identity and rearrange,
   remove, and add new components to make new-view. If any component is missing :id then give a warning and return
   false. The fallback applier will then be used."
  [attr-applier component ctx attr old-view new-view]
  ; TODO: remove the not-empty check. That's there to force use of the fallback until the main apply-children-applier-with-ids is implemented.
  ; I commented it out, and await some time before removing it all together.
  (if (and (every? :id (get old-view attr)) (every? :id (get new-view attr)) #_(not-empty (get new-view attr)))
    (apply-children-applier-with-ids attr-applier component ctx attr old-view new-view)
    (apply-children-applier-fallback attr-applier component ctx attr old-view new-view)))

(defn- get-sorted-attribute-keys
  [attr-appliers attr-keys]
  (loop [unsorted-attrs (set attr-keys) sorted-attrs []]
    (let [ready-attrs (filter (fn dependencies-met [attr]
                                (let [deps (get-in attr-appliers [attr :deps])]
                                  (or (not deps)
                                      (not (some unsorted-attrs deps)))))
                              unsorted-attrs)]
      (if (not-empty ready-attrs)
        (recur (apply disj unsorted-attrs ready-attrs) (into sorted-attrs ready-attrs))
        (if (not-empty unsorted-attrs)
          (do (log/warn "could not order attribute appliers. Circular dependence found. Returning all appliers anyway.")
              (into sorted-attrs unsorted-attrs))
          (do
            (log/info "sorted-attrs:" sorted-attrs)
            sorted-attrs))))))

(defn- default-applier-fn [c ctx v])

(defn- get-applier-fn [attr-applier]
  (if (map? attr-applier)
    (get attr-applier :fn default-applier-fn)
    attr-applier))

(defn apply-attributes
  "Takes the diff of the old-view and new-view and applies only the changes to the onscreen component. The old-view is
  the one from the previous @app-ref render call. It is _not_ the one stored in the component. When children components
  are being used it's impossible to tell if the child component is associated with the previous old-view or not and
  some appliers work on the parent and not the component itself. For instance the :tab-title and :tab-tooltip for
  Swing's JTabbedPane. Those attributes occur on the children but make changes to the parent. If a child is removed
  then the parent (JTabbedPane in this case) may need updates based on the children sliding into place. That is, the
  child at 2 may become the child at 1 if the child at 0 is removed. The child at 1 then needs all its attributes
  updated to look like what should be rendered at spot 1. The component is reused. Avoid this by using :id on the
  rendered results."
  [{:keys [onscreen-component app-val old-view new-view attr-appliers] :as ctx}]
  #_(log/info "applying attributes" (:class new-view) "log msg3")
  #_(log/info "onscreen-component class =" (class onscreen-component))
  (when (not (= old-view new-view))                           ; short circuit - do nothing if old and new are equal.
    ; Use old-view and new-view to get attribute keys because a key may be removed and that will be treated like
    ; setting the value to nil (or false).
    (doseq [attr (get-sorted-attribute-keys attr-appliers (concat (keys old-view) (keys new-view)))]
      (when-let [attr-applier (get attr-appliers attr)]
        (log/info "applying attr:" attr)
        (cond
          ; TODO: mutual recursion here could cause stack overflow for deeply nested UIs? Will a UI ever be that deeply
          ; nested?? Still... I could use trampoline and make this the last statement. Though trampoline may not help
          ; since apply-children-applier iterates over a sequence and calls apply-attributes. That iteration would have
          ; to be moved to apply-attributes or recursive itself.
          (children-applier? attr-applier) (apply-children-applier attr-applier onscreen-component ctx attr old-view new-view)
          (component-applier? attr-applier) (apply-component-applier attr-applier onscreen-component ctx attr old-view new-view)
          ; Assume attr-applier is a fn and call it on the component.
          :else (if (or (not (= (get old-view attr) (get new-view attr)))
                          (not (= (contains? old-view attr) (contains? new-view attr))))
                  (do
                    (log/info "reason for attr change? not=?" (not (= (get old-view attr) (get new-view attr))) " contains not=?" (not (= (contains? old-view attr) (contains? new-view attr))) "old nil?" (nil? (get old-view attr)) "new nil?" (nil? (get new-view attr)))
                    #_(let [old-val (get old-view attr)
                          new-val (get new-view attr)
                          new-s (str old-val)
                          old-s (str new-val)]
                      (log/info "old attr val =" (subs new-s 0 (min (count new-s) 100)))
                      (log/info "new attr val =" (subs old-s 0 (min (count old-s) 100)))
                      (log/info "diff between vals" (diff old-val new-val)))
                    (run-on-toolkit-thread ctx (get-applier-fn attr-applier) onscreen-component
                                           (assoc ctx :attr attr)
                                           (get new-view attr)))
                  (log/info "skipping applying attr because it is unchanged:" attr)))))
    (assoc-view ctx onscreen-component new-view))
  onscreen-component)

(defn instantiate-class
  [ctx ui]
  (let [app-val (:app-val ctx)
        retroact-config (:retroact app-val)
        ;_ (log/info "retroact: " retroact-config)
        id (:class ui)
        ;_ (log/info "building ui for" id "log msg4")
        final-class-map (merge (get-in-toolkit-config ctx :class-map) (:class-map retroact-config))
        class-or-fn (get-in final-class-map [(:class ui)] (get final-class-map :default))]
    (run-on-toolkit-thread-with-result
      ctx
      #(cond
         (instance? Class class-or-fn)
         (.newInstance (.getConstructor class-or-fn (into-array Class [])) (into-array Object []))
         (fn? class-or-fn) (class-or-fn ui)
         :else (log/error "no class or fn found to create component!")))
    ))

; TODO: perhaps build-ui is more like build-object because :mig-layout is not a UI. And the way things are setup,
; any object can be built with this code.
(defn build-ui
  "Take a view and realize it."
  [ctx view]
  (let [onscreen-component (instantiate-class ctx view)]
    (apply-attributes (assoc (dissoc ctx :old-view) :onscreen-component onscreen-component :new-view view))))


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

(defn- run-on-app-ref-chan
  [app-ref cmd]
  (go (>! (get-in (meta app-ref) [:retroact :update-view-chan]) cmd)))

(defn- run-cmd
  [cmd]
  (go (>! @retroact-cmd-chan cmd)))

(defn run-later [f & args]
  (let [cmd [:call-fn f args]]
    (go (>! @retroact-cmd-chan cmd))))

(defn- trigger-update-view [app-ref app-val]
  (run-on-app-ref-chan app-ref [:update-view app-ref app-val]))

(defn app-watch
  [watch-key app-ref old-value new-value]
  ; Update view when state changed
  (when (not= old-value new-value)
    (call-side-effects app-ref old-value new-value)
    (trigger-update-view app-ref new-value)))

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

(defn- recreate-attrs-changed? [onscreen-component attr-appliers old-view new-view]
  (let [new-attrs (keys new-view)]
    (some (fn [attr]
            (let [recreate-classes (get-in attr-appliers [attr :recreate] [])]
              (some (fn [klass] (and (instance? klass onscreen-component) (not= (get old-view attr) (get new-view attr))))
                    recreate-classes)))
          new-attrs)))

(defn- onscreen-component-reusable? [{:keys [attr-appliers] :as ctx} onscreen-component old-view new-view]
  (cond
    (nil? onscreen-component) false
    (not= (:class old-view) (:class new-view)) false
    (recreate-attrs-changed? onscreen-component attr-appliers old-view new-view) false
    :else true))

(defn- update-onscreen-component [{:keys [onscreen-component old-view new-view] :as ctx}]
  (let [reusable (onscreen-component-reusable? ctx onscreen-component old-view new-view)
        onscreen-component (if reusable onscreen-component (instantiate-class ctx new-view))
        ctx (if reusable ctx (assoc ctx :old-view (get-view ctx onscreen-component)))]
    (apply-attributes (assoc ctx :onscreen-component onscreen-component))
    onscreen-component))

(defn- get-render-fn [comp]
  (get comp :render (fn default-render-fn [app-ref app-value]
                      (log/warn "component did not provide a render fn"))))

(defn- is-component? [v]
  (and (map? v) (contains? v :render)))

(defn- run-lifecycle-fn [ctx comp fn-key]
  (when-let [lifecycle-fn (get comp fn-key)]
    (when (fn? lifecycle-fn)
      (try (lifecycle-fn (:onscreen-component comp) (:app-ref ctx) (:app-val ctx))
           (catch Exception ex
             (log/error ex "exception thrown while running" fn-key))))))

(defn- run-component-did-update [ctx comp] (run-lifecycle-fn ctx comp :component-did-update))
(defn- run-component-did-remount [ctx comp] (run-lifecycle-fn ctx comp :component-did-remount))
(defn- run-component-did-mount [ctx comp] (run-lifecycle-fn ctx comp :component-did-mount))

(defn- update-component
  "Update onscreen-component, create it if necessary, and return the new view and updated onscreen-component."
  [ctx comp]
  (let [{:keys [app-ref app-val]} ctx
        render (get-render-fn comp)
        view (:view comp)
        onscreen-component (:onscreen-component comp)
        update-onscreen-component (or (:update-onscreen-component comp) update-onscreen-component)
        new-view (render app-ref app-val)
        updated-onscreen-component
        (if (or (not onscreen-component) (not= view new-view))
          (update-onscreen-component
            (assoc ctx
              :onscreen-component onscreen-component
              :old-view view :new-view new-view))
          onscreen-component)
        updated-comp (assoc comp :view new-view :onscreen-component updated-onscreen-component)]
    (when (and (nil? onscreen-component) (not (nil? updated-onscreen-component)))
      (run-component-did-mount ctx updated-comp))
    (when (and onscreen-component updated-onscreen-component)
      (run-component-did-update ctx updated-comp))
    (assoc comp :view new-view :onscreen-component updated-onscreen-component)))

(defn- update-components [ctx components]
  ; app has a value for components representing the retroact views of the components, but components contains the
  ; component objects matching the onscreen-components. So we use it.
  (reduce-kv
    (fn render-onscreen-comp [m comp-id comp]
      (assoc m comp-id (update-component ctx comp)))
    ; This is the only reference to components in app. All other references to components is the local components to
    ; the retroact-main-loop. It's here because there may be multiple apps running and the main loop services them all.
    ; When the app state changes, only the components for that app are to be updated. I can make this so by storing the
    ; relationship between apps and components in the main loop instead of inside the app state. Components may be added
    ; or removed, and they are added to app... this is why the app must be used. There is already a map from chan to
    ; components. If added components go there then there's no need for using app here.
    ; To do that, I can just add another command: :add-component
    {} components))

(defn- clear-weak-refs-and-add-id->comp [state id comp]
  (let [ids-for-destroyed-comps (map first (filter (fn [[_ wr]] (nil? (.get wr))) (:id->comp state)))
        id->comp (apply dissoc (:id->comp state) ids-for-destroyed-comps)]
    (assoc state :id->comp (assoc id->comp id (WeakReference. comp)))))

(defn- register-component-with-id
  [onscreen-component ctx id]
  (log/info "registering component with id" id onscreen-component)
  (swap! retroact-state clear-weak-refs-and-add-id->comp id onscreen-component)
  (log/info "retroact-state =" (:id->comp @retroact-state)))

(def retroact-attr-appliers
  {:id register-component-with-id})

(defn get-comp [id]
  (log/info "getting comp with id" id)
  (let [weak-ref (get-in @retroact-state [:id->comp id])]
    (if weak-ref
      (do
        (log/info "got weak-ref:" weak-ref)
        (log/info "got comp:" (.get weak-ref))
        (.get weak-ref))
      (do
        (log/info "weak-ref is nil? " weak-ref))
      )))

(defn- debug-output-components [app-ref->components]
  (doseq [[app-ref components] app-ref->components]
    (doseq [[comp-id component] components]
      (log/info comp-id "->" (:onscreen-component component)))))

(defn- create-ctx [app-ref app-val]
  (let [ctx {:app-ref app-ref :app-val app-val}
        ctx (assoc ctx :attr-appliers (merge (get-in-toolkit-config ctx :attr-appliers)
                                             (get-in app-val [:retroact :attr-appliers])
                                             retroact-attr-appliers))]
    ctx))

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
        :update-view (let [[_ app-ref app-val] val
                           ctx (create-ctx app-ref app-val)
                           components (get app-ref->components app-ref {})
                           next-components (update-components ctx components)
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
        :add-component (let [{:keys [component app-ref app-val]} (second val)
                             ctx (create-ctx app-ref app-val)
                             components (get app-ref->components app-ref {})
                             comp-id (:comp-id component)
                             component-exists (contains? components comp-id)
                             component (if component-exists
                                         (merge (get components comp-id) component)
                                         component)
                             next-component (update-component ctx component)
                             next-components (assoc components comp-id next-component)]
                         (when (and component-exists (:onscreen-component next-component))
                           (run-component-did-remount ctx next-component))
                         (recur chans (assoc app-ref->components app-ref next-components)))
        :remove-component (let [{:keys [comp-id app-ref]} (second val)
                                next-components (dissoc (get app-ref->components app-ref) comp-id)
                                next-app-ref->components (assoc app-ref->components app-ref next-components)]
                            (recur chans next-app-ref->components))
        :call-fn (let [[_ f args] val]
                     (apply f args)
                     (recur chans app-ref->components))
        :debug-output-components (do (debug-output-components app-ref->components)
                                     (recur chans app-ref->components))
        :shutdown (do)                                      ; do nothing, will not recur
        (do (log/error "unrecognized command to retroact-cmd-chan, ignoring:" cmd-name)
            (recur chans app-ref->components)))
      )))

(defn- retroact-main []
  (let [retroact-cmd-chan (chan (buffer 100))
        retroact-thread (Thread. (fn retroact-main-runnable [] (retroact-main-loop retroact-cmd-chan)))]
    (.setName retroact-thread (str "Retroact-" (gen-retroact-thread-id)))
    (.start retroact-thread)
    retroact-cmd-chan))

(defonce retroact-cmd-chan (delay (retroact-main)))

(defn- alter-app-ref! [app-ref f & args]
  "Alters value of app-ref by applying f to the current value. Works on ref/atom/agent types and returns a valid value
  of the ref at some point. For atoms and refs the value is guaranteed to be the value immediately following the alter.
  For agents the value is non-deterministic but will be a valid value at some point in the agent's history."
  (condp instance? app-ref
    Atom (apply swap! app-ref f args)
    Ref (dosync (apply alter app-ref f args) @app-ref)
    Agent (do (apply send app-ref f args) @app-ref)))

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
  (run-cmd [:remove-component {:app-ref app-ref :comp-id comp-id}]))

(defn create-comp
  "Create a new top level component. There should not be many of these. This is akin to a main window. In the most
   extreme cases there may be a couple of hundred of these. In a typical case there will be between one component and a
   half a dozen components. The code is optimized for a small number of top level components. Legacy apps that wish to
   mount components in an existing non-Retroact component can use this to construct such \"detached\" components, which
   are essentially top level components as far as Retroact is concerned but not in the native windowing system. Note,
   the onscreen-component is built asynchronously and may be added to the legacy component in its component-did-mount
   or ... TODO: in the future there may be another way to do this.

   If :comp-id exists in comp then an existing top level component will be reused if one exists matching the supplied
   value of :comp-id."
  ([app-ref comp props]
   (let [constructor (get comp :constructor (fn default-constructor [props state] state))
         ; TODO: use the component id provided by the user in the view (:id) if one exists. If the user specifies an
         ; :id that is already being used then consider it the same component and either update the existing component
         ; or throw an error. I need to decide which is better.
         comp-id (get comp :comp-id (keyword (gensym "comp")))
         ; Add a unique id to ensure component map is unique. Side effects by duplicate components should
         ; generate duplicate onscreen components and we need to be sure the data here is unique. Onscreen
         ; components store Java reference in comp, but it won't be here immediately.
         comp (assoc comp :comp-id comp-id)]
     ; No need to render comp view here because this will trigger the watch, which will render the view.
     ; Not sure that previous statement is accurate. The watch will get triggered, but this new component isn't yet
     ; in the list of registered components to be rendered. So it doesn't get rendered until run-cmd :add-component
     ; executes, which has its own call to update-component, which in turn renders the view.
     (let [app
           (alter-app-ref! app-ref
                           (fn add-component-to-app [app]
                             (constructor props app)))]
       (when-let [side-effect (:side-effect comp)]
         #_(log/info "registering side effect for" comp "log msg7")
         (alter-meta! app-ref register-side-effect (check-side-effect-structure side-effect)))
       (run-cmd [:add-component {:app-ref app-ref :app-val app :component comp}])
       comp-id)))
  ([app-ref comp] (create-comp app-ref comp {})))

(defn- register-comp-type
  [class-key klass attr-appliers app-val]
  (-> (update-in app-val [:retroact :class-map] merge {class-key klass})
      (update-in [:retroact :attr-appliers] merge attr-appliers)))

(defn register-comp-type!
  "Add custom components and their attribute appliers. This may be useful when extending a Java Swing component, for
   instance - though preference should be given to using the existing components when possible."
  [app-ref class-key klass attr-appliers]
  ; This partial is no longer necessary, but I hesitate to modify it until I have time to test. If I change it then
  ; app-val in register-comp-type will need to be moved to the first argument position
  (alter-app-ref! app-ref (partial register-comp-type class-key klass attr-appliers))
  app-ref)

(defn init-app-ref
  "Initialize ref/atom/agent to work as state and \"app\" for Retroact components. If an application already has its
   own ref for handling state, this fn will allow the app to use that ref with Retroact instead of the default atom
   based empty state created by Retroact. There's no difference between using this and init-app. init-app is just a
   shortcut to use a default atom based ref."
  ([app-ref]
   (let [update-view-chan (chan (sliding-buffer 1))]
     (alter-meta! app-ref assoc :retroact {:update-view-chan update-view-chan})
     (alter-app-ref! app-ref assoc-in [:retroact :toolkit-config] swing/toolkit-config)
     (go (>! @retroact-cmd-chan [:update-view-chan update-view-chan]))
     (swap! retroact-state update-in [:app-refs] conj app-ref)
     ; no need to start thread here... it automatically starts when the retroact-cmd-chan is used.
     (add-watch app-ref :retroact-watch app-watch)
     #_(binding [*print-meta* true]
       (prn app-ref))
     app-ref)))

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
   (init-app-ref (atom {})))
  ([comp] (init-app comp {}))
  ([comp props]
   (let [app-ref (init-app)]
     (create-comp app-ref comp props)
     app-ref)))
