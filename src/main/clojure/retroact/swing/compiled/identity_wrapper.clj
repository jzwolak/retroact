(ns retroact.swing.compiled.identity-wrapper)

; Not used as of 2024-06-06 - this exists to make the hash code equal to the identityHashCode. This way mutable objects
; can be used as Java HashMap keys. IdentityHashMap won't do because I'm trying to also use WeakReference as in
; WeakHashMap.

(gen-class
  :name "retroact.swing.compiled.identity_wrapper.IdentityWrapper"
  :state "constantHashCode"
  :init "init-state"
  :prefix "identity-wrapper-"
  :constructors {[Object] []}
  :exposes-methods {hashCode superHashCode
                    equals superEquals})

(defn identity-wrapper-init-state [object]
  [[] (System/identityHashCode object)])

(defn identity-wrapper-hashCode [this]
  (.constantHashCode this))

(defn identity-wrapper-equals [this other]
  (and
    (not (nil? other))
    (= (.getClass this) (.getClass other))
    (= (.hashCode this) (.hashCode other))))