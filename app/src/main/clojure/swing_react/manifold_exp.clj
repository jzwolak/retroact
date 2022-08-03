(ns swing-react.manifold-exp
  (:require [manifold.stream :as ms]
            [swing-react.manifold-support :as mss]))

; An experiment with manifold

; Indeed, `consume` fn causes synchronous calls to the callback fn, which blocks `put!` until callback completes.

; take! does not block.
; dereferencing a deferred value blocks.

(defn stream-consumer [my-stream]
  (loop []
    (let [dval (ms/take! my-stream)]
      (println "consumed" @dval))
    (Thread/sleep 1000)
    (recur)))

(defn main []
  (let [my-stream (mss/dropping-stream 1)
        my-thread (Thread. ^Runnable (fn stream-runnable [] (stream-consumer my-stream)))]
    (.start my-thread)
    my-stream))
