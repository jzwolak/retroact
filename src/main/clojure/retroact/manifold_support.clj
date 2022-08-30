(ns retroact.manifold-support
  (:require [manifold.stream :as ms]
            [manifold.deferred :refer [let-flow]]))

; Copied from GitHub:
; https://github.com/clj-commons/manifold/issues/129
(defn dropping-stream
  "Wraps the `source` buffer with a stream with buffer size `n` which will drop items.

  If no source is specified, creates a new stream with the provided capacity."
  ([n]
   (let [in  (ms/stream)
         out (dropping-stream n in)]
     (ms/splice in out)))
  ([n source]
   (let [result (ms/stream n)]
     (ms/connect-via
       source
       (fn [val]
         (let-flow [put-result (ms/try-put! result val 0 :timeout)]
                   (case put-result
                     true     true
                     false    false
                     :timeout true)))
       result
       {:upstream?   true
        :downstream? true})
     result)))
