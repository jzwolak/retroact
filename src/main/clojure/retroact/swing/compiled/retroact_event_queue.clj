(ns retroact.swing.compiled.retroact-event-queue
  (:require [clojure.tools.logging :as log])
  (:import (java.awt EventQueue)
           (retroact.swing.compiled.retroact_invocation_event RetroactInvocationEvent)))

(gen-class
  :name "retroact.swing.compiled.retroact_event_queue.RetroactEventQueue"
  :extends java.awt.EventQueue
  :state "state"
  :init "init-state"
  ;  :post-init "post-init"
  :prefix "r-"
  :constructors {[java.util.Set                             ; retroact-events-in-queue
                  ]
                 []}
  :exposes-methods {dispatchEvent superDispatchEvent
                    postEvent superPostEvent})

(defn- update-retroact-events-in-queue
  [retroact-events-in-queue event]
  (if (and (or (instance? RetroactInvocationEvent event)
               (.contains retroact-events-in-queue (EventQueue/getCurrentEvent)))
           (not (nil? event)))
    (do
      (log/info (Exception. "stacktrace") "adding event to retroact-events-in-queue: event =" event)
      (.add retroact-events-in-queue event))
    (log/info (Exception. "stacktrace") "not adding event to retroact-events-in-queue: event =" event)))

(defn r-init-state [retroact-events-in-queue]
  [[] retroact-events-in-queue])

(defn r-dispatchEvent [this event]
  (let [retroact-events-in-queue (.state this)]
    (try
      ; This occurs here and in postEvent because some events get passed _directly_ to dispatchEvent.
      #_(log/info "EventQueue.dispatchEvent(): event =" event)
      (update-retroact-events-in-queue retroact-events-in-queue event)
      (.superDispatchEvent this event)
      (finally
        ; remove event from set of events being processed
        (let [size-before (.size retroact-events-in-queue)]
          (when (.remove retroact-events-in-queue event)
            #_(log/info "dispatched event, removing from queue: event =" event)
            #_(log/info "size before and after:" size-before (.size retroact-events-in-queue))))))))

(defn r-postEvent [this event]
  ; add event to set of events being processed
  #_(log/info "EventQueue.postEvent(): event =" event)
  (update-retroact-events-in-queue (.state this) event)
  (.superPostEvent this event))