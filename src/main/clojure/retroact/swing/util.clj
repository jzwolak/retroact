(ns retroact.swing.util
  (:import (java.awt Component)
           (javax.swing JComponent)
           (java.util WeakHashMap)
           (retroact.swing.compiled.identity_wrapper IdentityWrapper)))

(def client-prop-prefix "retroact-")

(defonce silenced-events (atom #{}))

; put object as key and IdentityWrapper as value in :object->identity. No get will ever be called. This is just to
; create a reference to the IdentityWrapper so it won't be garbage collected until the component is garbage collected.
; Note that this means we don't care about changes to the hash code because we're never looking up the component in
; this map. In addition, the component's weak ref key can be removed because even if the hash code has changed since the
; component was added, it doesn't matter, the WeakHashMap would not be able to get the hash code anyway.
; The :identity->props WeakHashMap will have an IdentityWrapper as the key (same as the IdentityWrapper that appears as
; the value for the component in the previous map) and this is used to actually lookup the component's properties.
(defonce object->identity (WeakHashMap.))
(defonce identity->props (WeakHashMap.))

(defmulti get-client-prop
          "Call on EDT. Gets a client property for all objects including non-JComponent."
          (fn [comp name] (class comp)))
(defmethod get-client-prop JComponent [comp name]
  (let [val
        (.getClientProperty comp (str client-prop-prefix name))]
    val))
(defmethod get-client-prop Object [comp name]
  (let [id-wrapper (IdentityWrapper. comp)]
    (get-in identity->props [id-wrapper name])))

(defmulti set-client-prop
          "Call on EDT. Sets a client property and has a mechanism for non-JComponent objects to set a property."
          (fn [comp name value] (class comp)))
(defmethod set-client-prop JComponent [comp name value]
  (.putClientProperty comp (str client-prop-prefix name) value))
(defmethod set-client-prop Object [comp name value]
  ; ignore. Or possibly in the future use an internal map of (map comp -> ( map name -> value) )
  ; Which would require removing things from the map when they are removed from the view. WeakReference may help with
  ; this.
  ; The following will fail concurrency. Because object->identity and identity->props are WeakHashMap objects modified
  ; within swap!. Furthermore, two calls to put could happen simultaneously from different threads.
  (let [id-wrapper (IdentityWrapper. comp)
        props (get identity->props id-wrapper {})]
    (when (not (contains? identity->props id-wrapper))
      (.put object->identity comp id-wrapper))
    (.put identity->props id-wrapper (assoc props name value))))

(defn get-view [onscreen-component]
  (get-client-prop onscreen-component "view")
  ; TODO: this loop should really not be necessary and may even cause problems. The view should always be on the
  ; component or just not present in the case of non-JComponent components.
  #_(loop [oc onscreen-component]
      #_(log/info "get-view got view =" (apply str (take 100 (if oc (get-client-prop oc "view")))))
      (let [view (if oc (get-client-prop oc "view"))]
        (cond
          view view
          (or (nil? oc) (not (instance? Component oc))) nil
          :else (recur (.getParent oc))))))

(defn assoc-view [onscreen-component view]
  (set-client-prop onscreen-component "view" view))

(defn assoc-ctx [onscreen-component ctx]
  #_(log/info "assoc-ctx, ctx :new-view =" (:new-view ctx))
  (set-client-prop onscreen-component "ctx" ctx))

(defn get-ctx [onscreen-component]
  (let [ctx (if onscreen-component (get-client-prop onscreen-component "ctx"))]
    (cond
      ctx ctx
      (or (nil? onscreen-component) (not (instance? Component onscreen-component))) nil
      :else (recur (.getParent onscreen-component)))))

(defn get-comp-id [onscreen-component]
  (if-let [comp-id (get-client-prop onscreen-component "comp-id")]
    comp-id
    (let [comp-id (str (gensym "comp-id-"))]
      (set-client-prop onscreen-component "comp-id" comp-id)
      comp-id)))

