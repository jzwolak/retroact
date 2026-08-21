(ns retroact.jtree-test
  (:require [clojure.test :refer :all]
            [retroact.swing.jtree :as rjtree])
  (:import (javax.swing JTree SwingUtilities)
           (javax.swing.tree TreePath)))

;; The tree data used by these tests is [parents children selection expansion]
;; where `parents` are the root's children in order, `children` maps a parent to
;; its children, and `selection` / `expansion` are vectors of nodes. Both fns
;; below derive paths from that data the way an app derives them from its state.

(defn- test-tree-model-fn [tree-root [parents children]]
  [tree-root (into {tree-root parents} children)])

(defn- paths-to
  "Every path in `tree` ending at one of `nodes`, rooted at `tree-root`."
  [tree tree-root nodes]
  (let [wanted (set nodes)]
    (letfn [(walk [path node]
              (let [path (conj path node)]
                (concat (when (wanted node) [path])
                        (mapcat (partial walk path) (get tree node)))))]
      (vec (mapcat (partial walk [tree-root]) (get tree tree-root))))))

(defn- test-tree-selection-fn [tree-root tree [_ _ selection]]
  (paths-to tree tree-root selection))

(defn- test-tree-expansion-fn [tree-root tree [_ _ _ expansion]]
  (paths-to tree tree-root expansion))

(defn- make-tree
  ([data] (make-tree data false))
  ([data expansion-from-data?]
   (let [^JTree tree (rjtree/create-jtree {})]
     (rjtree/set-tree-model-fn tree nil test-tree-model-fn)
     (rjtree/set-tree-selection-fn tree nil test-tree-selection-fn)
     (when expansion-from-data?
       (rjtree/set-tree-expansion-fn tree nil test-tree-expansion-fn))
     (rjtree/set-tree-data tree nil data)
     tree)))

(defn- path-of [^JTree tree & nodes]
  (TreePath. ^"[Ljava.lang.Object;" (into-array Object (into [(.getRoot (.getModel tree))] nodes))))

(defn- on-edt
  "Run f on the EDT — RTreeModel expects to be driven from there. bound-fn*
   carries clojure.test's report counters across so assertions still count."
  [f]
  (SwingUtilities/invokeAndWait (bound-fn* f)))

(def ^:private children {"a" ["a1" "a2"] "b" ["b1"] "a1" ["a1x"]})

(deftest expansion-follows-the-data
  (on-edt
    (fn expansion-follows-the-data-on-edt []
      (let [tree (make-tree [["a" "b"] children [] []] true)]
        (is (not (.isExpanded tree (path-of tree "a"))) "nothing is expanded to start")
        (rjtree/set-tree-data tree nil [["a" "b"] children [] ["a"]])
        (is (.isExpanded tree (path-of tree "a")) "data says a is expanded")
        (rjtree/set-tree-data tree nil [["a" "b"] children [] []])
        (is (not (.isExpanded tree (path-of tree "a"))) "data says a is closed again")))))

(deftest expansion-survives-a-structure-change
  (on-edt
    (fn expansion-survives-a-structure-change-on-edt []
      (let [tree (make-tree [["a" "b"] children [] ["a"]] true)]
        (is (.isExpanded tree (path-of tree "a")) "precondition: a is expanded")
        ;; A sibling arrives — the tree structure changed, which is what makes
        ;; JTree drop its expansion.
        (rjtree/set-tree-data tree nil [["a" "b" "c"] (assoc children "c" ["c1"]) [] ["a"]])
        (is (.isExpanded tree (path-of tree "a"))
            "the state still says a is expanded, so the view must show it expanded")))))

(deftest expansion-survives-a-reorder
  (on-edt
    (fn expansion-survives-a-reorder-on-edt []
      (let [tree (make-tree [["a" "b"] children [] ["a"]] true)]
        (rjtree/set-tree-data tree nil [["b" "a"] children [] ["a"]])
        (is (.isExpanded tree (path-of tree "a")))))))

(deftest nested-expansion-survives-a-structure-change
  (on-edt
    (fn nested-expansion-survives-a-structure-change-on-edt []
      (let [tree (make-tree [["a"] children [] ["a" "a1"]] true)]
        (is (.isExpanded tree (path-of tree "a" "a1")) "precondition: a1 is expanded")
        (rjtree/set-tree-data tree nil [["a" "b"] children [] ["a" "a1"]])
        (is (.isExpanded tree (path-of tree "a")))
        (is (.isExpanded tree (path-of tree "a" "a1"))
            "a child stays expanded, and only because its parent is expanded first")))))

(deftest expansion-of-a-node-the-data-dropped-is-ignored
  (on-edt
    (fn expansion-of-a-node-the-data-dropped-is-ignored-on-edt []
      (let [tree (make-tree [["a" "b"] children [] ["a"]] true)]
        ;; "a" is filtered out of the data; naming it expanded must not throw.
        (rjtree/set-tree-data tree nil [["b"] children [] ["a"]])
        (is (not (.isExpanded tree (path-of tree "a"))))
        ;; ...and it comes back expanded when the data brings it back.
        (rjtree/set-tree-data tree nil [["a" "b"] children [] ["a"]])
        (is (.isExpanded tree (path-of tree "a")))))))

(deftest without-an-expansion-fn-a-structure-change-still-collapses
  (on-edt
    (fn without-an-expansion-fn-a-structure-change-still-collapses-on-edt []
      (let [tree (make-tree [["a" "b"] children [] []])]
        (.expandPath tree (path-of tree "a"))
        (is (.isExpanded tree (path-of tree "a")))
        (rjtree/set-tree-data tree nil [["a" "b" "c"] (assoc children "c" ["c1"]) [] []])
        (is (not (.isExpanded tree (path-of tree "a")))
            "plain JTree behaviour is left alone for apps that do not hold expansion")))))

(deftest selection-survives-a-structure-change
  (on-edt
    (fn selection-survives-a-structure-change-on-edt []
      (let [tree (make-tree [["a" "b"] children ["a"] []] true)]
        (is (= [(path-of tree "a")] (vec (.getSelectionPaths tree)))
            "precondition: a is selected")
        (rjtree/set-tree-data tree nil [["a" "b" "c"] (assoc children "c" ["c1"]) ["a"] []])
        (is (= [(path-of tree "a")] (vec (or (.getSelectionPaths tree) [])))
            "the state still says a is selected, so the view must show it selected")))))
