(ns retroact.test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [retroact.core :refer [init-app-ref create-comp init-app]]
            [retroact.algorithms.core :refer [calculate-lcs calculate-patch-operations]]
            [examples.greeter :refer [greeter-app]]))

(defn hello-world-app []
  {:render (fn [app-ref app]
             {:class :frame
              :contents [{:class :label :text "Hello World!"}]})})

(deftest basic-atom
  (let [app-ref (atom {})]
    (init-app-ref app-ref)
    (create-comp app-ref (hello-world-app))))

(deftest basic-ref
  (let [app-ref (ref {})]
    (init-app-ref app-ref)
    (create-comp app-ref (hello-world-app))))

(deftest basic-agent
  (let [app-ref (agent {})]
    (init-app-ref app-ref)
    (create-comp app-ref (hello-world-app))))

(deftest longest-common-subsequence
  (let [old-view {:contents [{:id :a} {:id :b} {:id :c} {:id :d} {:id :e} {:id :f} {:id :g}]}
        new-view {:contents [{:id :b} {:id :g} {:id :a} {:id :h} {:id :c} {:id :f}]}]
    (is (= [{:id :b} {:id :c} {:id :f}] (calculate-lcs (:contents old-view) (:contents new-view)))
        "Greatest common subsequence")))

(deftest patch-operations
  (let [old-view {:contents [{:id :a} {:id :b} {:id :c} {:id :d} {:id :e} {:id :f} {:id :g}]}
        new-view {:contents [{:id :b} {:id :g} {:id :a} {:id :h} {:id :c} {:id :f}]}]
    (is (= [[[:remove 6 {:id :g}] [:remove 4 {:id :e}] [:remove 3 {:id :d}] [:remove 0 {:id :a}]] [[:insert 1 {:id :h}] [:insert 1 {:id :a}] [:insert 1 {:id :g}]]]
           (calculate-patch-operations (:contents old-view) (:contents new-view)))
        "Patch operations")
    (is (= [[[:remove 0 :a]] []]
           (calculate-patch-operations [:a] [])))
    (is (= [[] [[:insert 0 :a]]]
           (calculate-patch-operations [] [:a])))
    (is (= [[] []]
           (calculate-patch-operations [] [])))
    (is (= [[] []]
           (calculate-patch-operations [:a :b] [:a :b])))))

#_(deftest greeter-example
  (let [app-ref (init-app (greeter-app))]
    ; TODO: press button in greeter
    ; TODO: verify color changed and message label added
    ; TODO: close greeter
    ; TODO: verify frame disappeared and was properly deallocated
    ))

(run-tests)

; Not sure why this was here, but it prevents rerunning tests from REPL.
#_(shutdown-agents)
