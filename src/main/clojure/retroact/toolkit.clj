(ns retroact.toolkit
  (:require [clojure.core.async :refer [chan <!! >!!]]
            [clojure.tools.logging :as log]
            [retroact.error-handling :refer [capture-stack]]))

; This namespace holds fns for storing and retrieving config for toolkit fns, methods, and classes. It also provides
; a means to execute toolkit specific fns. This namespace is not toolkit specific and does not reference any particular
; toolkit.

(defn get-in-toolkit-config [ctx k]
  (if-let [unresolved-symbol (get-in ctx [:app-val :retroact :toolkit-config k])]
    @(resolve unresolved-symbol)
    (do
      (log/warn "could not resolve toolkit-config symbol for key" k)
      (log/warn "is app state initialized?"))))

(defn run-on-toolkit-thread [ctx f & args]
  (let [tk-run-on-toolkit-thread (get-in-toolkit-config ctx :run-on-toolkit-thread)
        f-in-stack-prison (capture-stack ctx f)]
    (apply tk-run-on-toolkit-thread f-in-stack-prison args)))

(defn run-on-toolkit-thread-with-result [ctx f & args]
  (let [result-chan (chan 1)]
    (run-on-toolkit-thread
      ctx
      (fn run-with-result []
        (log/info "run-with-result:" f)
        (let [result (try [::success (apply f args)]
                          (catch Exception ex
                            (log/info "exception while running" f)
                            [::exception ex]))]
          (>!! result-chan result))))
    (let [[result-status result-value] (<!! result-chan)]
      (cond
        (= result-status ::exception) (throw (Exception. "exception thrown while running on toolkit thread" result-value))
        :else result-value))))

(defn redraw-onscreen-component [ctx component]
  (let [tk-redraw-onscreen-component (get-in-toolkit-config ctx :redraw-onscreen-component)]
    (run-on-toolkit-thread ctx tk-redraw-onscreen-component component)))

(defn assoc-view [ctx onscreen-component view]
  (let [tk-assoc-view (get-in-toolkit-config ctx :assoc-view)]
    (run-on-toolkit-thread ctx tk-assoc-view onscreen-component view)))

(defn get-view [ctx onscreen-component]
  (let [tk-get-view (get-in-toolkit-config ctx :get-view)]
    (run-on-toolkit-thread-with-result ctx tk-get-view onscreen-component)))

(defn assoc-ctx [ctx onscreen-component]
  (let [tk-assoc-ctx (get-in-toolkit-config ctx :assoc-ctx)]
    (run-on-toolkit-thread ctx tk-assoc-ctx onscreen-component ctx)))

(defn get-ctx [ctx onscreen-component]
  (let [tk-get-ctx (get-in-toolkit-config ctx :get-ctx)]
    (run-on-toolkit-thread-with-result ctx tk-get-ctx onscreen-component)))

