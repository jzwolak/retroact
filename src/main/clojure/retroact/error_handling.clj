(ns retroact.error-handling
  (:require [clojure.tools.logging :as log]))

(defn handle-uncaught-exception [ex]
  (let [current-thread (Thread/currentThread)
        uncaught-ex-handler (.getUncaughtExceptionHandler current-thread)]
    (.uncaughtException uncaught-ex-handler current-thread ex)))

(defn capture-stack [ctx f]
  (let [prison (RuntimeException. (str "captured stack for view " (select-keys (:new-view ctx) [:comp-id :name :class]) " and fn " f))]
    (fn [& args]
      (try
        (apply f args)
        (catch Exception cause
          (.initCause prison cause)
          (throw prison))))))

