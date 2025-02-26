(ns retroact.swing.event-queue
  (:require [clojure.tools.logging :as log])
  (:import (java.awt EventQueue Toolkit)
           (java.util Collections IdentityHashMap)
           (retroact.swing.compiled.retroact_event_queue RetroactEventQueue)
           (retroact.swing.compiled.retroact_invocation_event RetroactInvocationEvent)))

(defonce retroact-events-in-queue (delay (Collections/synchronizedSet (Collections/newSetFromMap (IdentityHashMap.)))))

(defn retroact-initiated? []
  (let [event (EventQueue/getCurrentEvent)
        retroact-event? (.contains @retroact-events-in-queue event)]
    #_(log/info "retroact-initiated?: event =" event)
    #_(log/info "is retroact event?" retroact-event?)
    #_(log/info "retroact event size =" (.size @retroact-events-in-queue))
    (when (and (instance? RetroactInvocationEvent event) (not retroact-event?))
      (log/warn "event is RetroactInvocationEvent and not detected as retroact event!" event)
      (log/warn (Exception. "get captured stack") "full stacktrace with captured stack" event)
      (throw (Exception. "get captured stack")))
    #_(when (> 3 (.size @retroact-events-in-queue))
      (log/info "outputting all events in queue...")
      (doseq [e @retroact-events-in-queue]
        (log/info "event in queue =" e)))
    retroact-event?))

(defn- update-retroact-events-in-queue
  [event]
  (if (and (or (instance? RetroactInvocationEvent event)
               (.contains @retroact-events-in-queue (EventQueue/getCurrentEvent)))
           (not (nil? event)))
    (do
      #_(log/info (Exception. "stacktrace") "adding event to retroact-events-in-queue: event =" event)
      (.add @retroact-events-in-queue event))
    #_(log/info (Exception. "stacktrace") "not adding event to retroact-events-in-queue: event =" event)))

(defn- register-event-queue
  "Call this on first event post to register the retroact custom event queue which tracks whether
  events originated from Retroact or elsewhere. Call it only once, which is why there is a `delay` to access it."
  []
  #_(log/info "event-queue =" (.getSystemEventQueue (Toolkit/getDefaultToolkit)))
  #_(log/info "installing retroact custom event queue")
  (->
    (Toolkit/getDefaultToolkit)
    (.getSystemEventQueue)
    (.push (RetroactEventQueue. @retroact-events-in-queue))
    #_(.push (proxy
             [EventQueue] []
             (createSecondaryLoop []
               #_(log/info "EventQueue.createSecondaryLoop()")
               (proxy-super createSecondaryLoop))
             (dispatchEvent
               [event]
               (try
                 ; This occurs here and in postEvent because some events get passed _directly_ to dispatchEvent.
                 #_(log/info "EventQueue.dispatchEvent(): event =" event)
                 (update-retroact-events-in-queue event)
                 (proxy-super dispatchEvent event)
                 (finally
                   ; remove event from set of events being processed
                   (let [size-before (.size @retroact-events-in-queue)]
                     (when (.remove @retroact-events-in-queue event)
                       #_(log/info "dispatched event, removing from queue: event =" event)
                       #_(log/info "size before and after:" size-before (.size @retroact-events-in-queue)))))))
             (getNextEvent []
               (let [event (proxy-super getNextEvent)]
                 #_(log/info (Exception. "stacktrace") "EventQueue.getNextEvent():" event)
                 event))
             (pop []
               (log/info "EventQueue.pop(): stopping processing events with retroact event queue")
               (throw (Exception. "do not pop! or you'll stop!"))
               (proxy-super pop))
             (postEvent
               [event]
               ; add event to set of events being processed
               #_(log/info "EventQueue.postEvent(): event =" event)
               (update-retroact-events-in-queue event)
               (proxy-super postEvent event))
             (push [event-queue]
               (log/info "EventQueue.push(): uninstalling retroact custom event queue and replacing with" event-queue)
               (proxy-super push event-queue))
             )))
  true)
(defonce event-queue-register (delay (register-event-queue)))