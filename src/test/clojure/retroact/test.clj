(ns retroact.test
  (:require [clojure.test :refer :all]
            [retroact.core :refer [init-app-ref create-comp init-app calculate-gcs]]
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

(deftest greatest-common-subsequence
  (let [old-view {:contents [{:id :a} {:id :b} {:id :c} {:id :d} {:id :e} {:id :f} {:id :g}]}
        new-view {:contents [{:id :b} {:id :g} {:id :a} {:id :h} {:id :c} {:id :f}]}]
    (is (= [:a :c :f] (calculate-gcs old-view new-view :contents))
        "Greatest common subsequence")))

#_(deftest greeter-example
  (let [app-ref (init-app (greeter-app))]
    ; TODO: press button in greeter
    ; TODO: verify color changed and message label added
    ; TODO: close greeter
    ; TODO: verify frame disappeared and was properly deallocated
    ))

(run-tests)
(shutdown-agents)
