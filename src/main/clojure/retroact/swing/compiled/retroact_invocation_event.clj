(ns retroact.swing.compiled.retroact-invocation-event
  (:require [clojure.tools.logging :as log]))

; TODO: is this still used? Delete if not.
(gen-class
  :name "retroact.swing.compiled.retroact_invocation_event.RetroactInvocationEvent"
  :extends java.awt.event.InvocationEvent
;  :state "state"
;  :init "init-state"
  ;  :post-init "post-init"
  :prefix "r-"
  :exposes-methods {dispatch superDispatch})

(defn r-dispatch [this]
  #_(log/info (Exception.) "dispatching" this)
  (.superDispatch this)
  #_(log/info (Exception.) "finished dispatching" this))
