(ns retroact.jtree-test
  (:require [clojure.test :refer :all]
            [retroact.swing.jtree :as rjtree])
  (:import (javax.swing JTree SwingUtilities)
           (javax.swing.tree TreePath)))

;; The tree data used by these tests is [parents children selection] where
;; `parents` are the root's children in order, `children` maps a parent to its
;; children, and `selection` is a vector of nodes (children of the root) that
;; should be selected.

(defn- test-tree-model-fn [tree-root [parents children]]
  [tree-root (into {tree-root parents} children)])

(defn- test-tree-selection-fn [tree-root _tree [_ _ selection]]
  (mapv (fn selection-path [node] [tree-root node]) selection))

(defn- make-tree
  ([data] (make-tree data nil))
  ([data node-identity-fn]
   (let [^JTree tree (rjtree/create-jtree {})]
     (rjtree/set-tree-model-fn tree nil test-tree-model-fn)
     (rjtree/set-tree-selection-fn tree nil test-tree-selection-fn)
     (when node-identity-fn
       (rjtree/set-tree-node-identity-fn tree nil node-identity-fn))
     (rjtree/set-tree-data tree nil data)
     tree)))

(defn- path-of [^JTree tree & nodes]
  (TreePath. ^"[Ljava.lang.Object;" (into-array Object (into [(.getRoot (.getModel tree))] nodes))))

(defn- on-edt
  "Run f on the EDT — RTreeModel expects to be driven from there. bound-fn*
   carries clojure.test's report counters across so assertions still count."
  [f]
  (SwingUtilities/invokeAndWait (bound-fn* f)))

(deftest expansion-survives-added-sibling
  (on-edt
    (fn expansion-survives-added-sibling-on-edt []
      (let [tree (make-tree [["a" "b"] {"a" ["a1" "a2"] "b" ["b1"]}])]
        (.expandPath tree (path-of tree "a"))
        (is (.isExpanded tree (path-of tree "a")) "precondition: node is expanded")
        (rjtree/set-tree-data tree nil [["a" "b" "c"] {"a" ["a1" "a2"] "b" ["b1"] "c" ["c1"]}])
        (is (.isExpanded tree (path-of tree "a"))
            "an expanded node must stay expanded when unrelated data arrives")))))

(deftest expansion-survives-reorder
  (on-edt
    (fn expansion-survives-reorder-on-edt []
      (let [children {"a" ["a1" "a2"] "b" ["b1"]}
            tree (make-tree [["a" "b"] children])]
        (.expandPath tree (path-of tree "a"))
        (rjtree/set-tree-data tree nil [["b" "a"] children])
        (is (.isExpanded tree (path-of tree "a"))
            "an expanded node must stay expanded when its siblings are reordered")))))

(deftest nested-expansion-survives-data-change
  (on-edt
    (fn nested-expansion-survives-data-change-on-edt []
      (let [children {"a" ["a1"] "a1" ["a1x"]}
            tree (make-tree [["a"] children])]
        (.expandPath tree (path-of tree "a"))
        (.expandPath tree (path-of tree "a" "a1"))
        (is (.isExpanded tree (path-of tree "a" "a1")) "precondition: child is expanded")
        (rjtree/set-tree-data tree nil [["a" "b"] (assoc children "b" ["b1"])])
        (is (.isExpanded tree (path-of tree "a")))
        (is (.isExpanded tree (path-of tree "a" "a1"))
            "expansion deeper in the tree must survive too")))))

(deftest expansion-of-removed-node-is-dropped
  (on-edt
    (fn expansion-of-removed-node-is-dropped-on-edt []
      (let [tree (make-tree [["a" "b"] {"a" ["a1"] "b" ["b1"]}])]
        (.expandPath tree (path-of tree "a"))
        ;; "a" is gone from the new data — restoring its expansion would be
        ;; meaningless, and must not throw.
        (rjtree/set-tree-data tree nil [["b"] {"b" ["b1"]}])
        (is (not (.isExpanded tree (path-of tree "a"))))
        (is (.isExpanded tree (path-of tree)) "the root stays expanded")))))

(deftest selection-survives-data-change
  (on-edt
    (fn selection-survives-data-change-on-edt []
      (let [tree (make-tree [["a" "b"] {"a" ["a1"] "b" ["b1"]} ["a"]])]
        (is (= [(path-of tree "a")] (vec (.getSelectionPaths tree)))
            "precondition: node is selected")
        (rjtree/set-tree-data tree nil [["a" "b" "c"] {"a" ["a1"] "b" ["b1"] "c" ["c1"]} ["a"]])
        (is (= [(path-of tree "a")] (vec (or (.getSelectionPaths tree) [])))
            "a selection the app still holds must survive a change to the tree data")))))

;; A node the app rewrites - ProcessDB loads sharing data onto its model maps a
;; second after the models themselves land - is a new value but the same node.

(defn- node-id [node]
  (if (map? node) (:id node) node))

(deftest expansion-survives-rewritten-node-value
  (on-edt
    (fn expansion-survives-rewritten-node-value-on-edt []
      (let [plain {:id "a"}
            rewritten (assoc plain :shared-with ["someone"])
            tree (make-tree [[plain] {plain ["a1"]}] node-id)]
        (.expandPath tree (path-of tree plain))
        (is (.isExpanded tree (path-of tree plain)) "precondition: node is expanded")
        (rjtree/set-tree-data tree nil [[rewritten] {rewritten ["a1"]}])
        (is (.isExpanded tree (path-of tree rewritten))
            "a node whose value the app rewrote is still the same node")))))

(deftest expansion-of-rewritten-node-needs-an-identity-fn
  (on-edt
    (fn expansion-of-rewritten-node-needs-an-identity-fn-on-edt []
      (let [plain {:id "a"}
            rewritten (assoc plain :shared-with ["someone"])
            tree (make-tree [[plain] {plain ["a1"]}])]
        (.expandPath tree (path-of tree plain))
        (rjtree/set-tree-data tree nil [[rewritten] {rewritten ["a1"]}])
        (is (not (.isExpanded tree (path-of tree rewritten)))
            "without an identity fn a rewritten value is a different node")))))
