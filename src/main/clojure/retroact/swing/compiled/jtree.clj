(ns retroact.swing.compiled.jtree
  (:require [clojure.tools.logging :as log])
  (:import (java.util UUID)
           (javax.swing SwingUtilities)
           (javax.swing.event TreeModelEvent)
           (javax.swing.tree TreePath)))

; :data - the data supplied and used to generate the tree
; :tree - the result of calling :tree-model-fn on data. This should have a tree structure.
; :tree-model-fn - ??
; :tree-render-fn takes a node in the :tree and turns it into something that can be displayed. A String will do.
(gen-class
  :name "retroact.swing.compiled.jtree.RTreeModel"
  :implements [javax.swing.tree.TreeModel]
  :state "state"
  :init "init-state"
  :post-init "post-init"
  :prefix "rtree-model-"
  :methods [[getNode [javax.swing.tree.TreePath] Object]
            [setData [clojure.lang.IPersistentVector] void]
            [setModelFn [clojure.lang.IFn] void]
            [setRenderFn [clojure.lang.IFn] void]])

(gen-class
  :name "retroact.swing.compiled.jtree.RTreeCellRenderer"
  :extends javax.swing.tree.DefaultTreeCellRenderer
  :prefix "rtree-cell-renderer-"
  :exposes-methods {getTreeCellRendererComponent superGetTreeCellRendererComponent})

(defn- default-tree-model-fn [default-tree-root data] [default-tree-root {default-tree-root data}])

(defn- tree-model-watch
  [this _key _ref old-value new-value]
  (let [old-tree (:tree old-value)
        new-tree (:tree new-value)
        listeners (:listeners new-value)]
    (when (not= old-tree new-tree)
      (let [tree-model-event (TreeModelEvent. this (object-array [(:tree-root new-value)]))]
        (doseq [listener listeners]
          (SwingUtilities/invokeLater
            #(.treeStructureChanged listener tree-model-event)))))))

(defn rtree-model-init-state []
  (let [tree-root (str (UUID/randomUUID))
        state (atom {:data   []
                     ; map of nodes to children
                     :tree   {tree-root []}
                     :tree-root tree-root
                     :tree-model-fn default-tree-model-fn
                     :tree-render-fn identity
                     :listeners #{}})]
    #_(add-watch state :tree-model-self-watch tree-model-watch)
    [[] state]))

(defn rtree-model-post-init [this & _]
  (add-watch (.state this) :tree-model-self-watch (partial tree-model-watch this)))

(defn- update-tree
  [state]
  (let [data (:data state)
        tree-model-fn (:tree-model-fn state)
        [tree-root tree] (tree-model-fn (:tree-root state) data)]
    (assoc state :tree tree
                 :tree-root tree-root)))

(defn- update-data [state data]
  (if (= (:data state) data)
    state
    (update-tree (assoc state :data data))))

(defn- get-node [tree tree-path]
  (let []))

(defn rtree-model-getNode
  [this tree-path]
  (get-node (:tree @(.state this)) tree-path))

(defn rtree-model-setData
  [this data]
  (swap! (.state this) update-data data))

(defn- update-model-fn [state model-fn]
  (if (= (:tree-model-fn state) model-fn)
    state
    (update-tree (assoc state :tree-model-fn model-fn))))

(defn rtree-model-setModelFn
  [this model-fn]
  (swap! (.state this) update-model-fn model-fn))

(defn rtree-model-addTreeModelListener
  [this listener]
  (swap! (.state this) update-in [:listeners] conj listener))

(defn rtree-model-getChild
  [this parent index]
  (let [state @(.state this)]
    (get-in state [:tree parent index])))

(defn rtree-model-getChildCount
  [this parent]
  (let [state @(.state this)
        child-count (count (get-in state [:tree parent]))]
    child-count))

(defn rtree-model-getIndexOfChild
  [this parent child]
  (let [state @(.state this)]
    (.indexOf (get-in state [:tree parent]) child))
  )

(defn rtree-model-getRoot
  [this]
  (:tree-root @(.state this)))

(defn rtree-model-isLeaf
  [this node]
  (not (contains? (:tree @(.state this)) node)))

(defn rtree-model-removeTreeModelListener
  [this listener]
  (swap! (.state this) update-in [:listeners] disj listener))

(defn rtree-model-valueForPathChanged
  [this path new-value]
  (throw (UnsupportedOperationException. "tree model cannot be modified")))

; TODO: setting the tree-render-fn while rendering is occurring could cause concurrency problems and race conditions
; if old data is not compatible with new render fn. I don't know how to handle this. Ideally, when rendering starts
; state would be deref'd and a value for data and the render fn would be used, but since the JTree is part of Swing
; I don't know where to put that referencing to make sure it happens at the right time.
(defn rtree-model-setRenderFn
  [this render-fn]
  (swap! (.state this) assoc :tree-render-fn render-fn))

(defn rtree-cell-renderer-getTreeCellRendererComponent
  [this tree value selected expanded leaf row has-focus]
  (let [tree-render-fn (:tree-render-fn @(.state (.getModel tree)))
        rendered-value (if tree-render-fn (tree-render-fn value) value)]
    (cond
      (string? rendered-value)
      (.superGetTreeCellRendererComponent this tree rendered-value selected expanded leaf row has-focus)
      (map? rendered-value)
      (do
          (when (contains? rendered-value :icon)
            (.setClosedIcon this (:icon rendered-value))
            (.setOpenIcon this (:icon rendered-value))
            (.setLeafIcon this (:icon rendered-value)))
          (.superGetTreeCellRendererComponent this tree (:value rendered-value) selected expanded leaf row has-focus))
      )))
