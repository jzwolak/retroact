(ns retroact.swing.compiled.jtree
  (:require [clojure.tools.logging :as log])
  (:import (java.awt EventQueue)
           (java.awt.event ComponentAdapter HierarchyEvent HierarchyListener)
           (java.util UUID)
           (javax.swing SwingUtilities)
           (javax.swing.event TreeModelEvent)
           (javax.swing.tree TreePath)))

; :data - the data supplied and used to generate the tree, this is raw data that can be quickly queried from the domain
;         model
; :tree - the result of calling :tree-model-fn on data. This should have a tree structure and may take a while to
;         calculate.
; :tree-model-fn - calculates :tree from :data and takes :tree-root and :data as its arguments.
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
            [setRenderFn [clojure.lang.IFn] void]
            [setTreeComponent [javax.swing.JTree] void]
            [setSelectionFn [clojure.lang.IFn] void]
            [setScrollPathFn [clojure.lang.IFn] void]])

(gen-class
  :name "retroact.swing.compiled.jtree.RTreeCellRenderer"
  :extends javax.swing.tree.DefaultTreeCellRenderer
  :prefix "rtree-cell-renderer-"
  :exposes-methods {getTreeCellRendererComponent superGetTreeCellRendererComponent})

(defn- default-tree-model-fn [default-tree-root data] [default-tree-root {default-tree-root data}])

(defn- print-tree-paths [print-prefix tree-paths]
  (cond
    (nil? tree-paths) (log/info print-prefix "tree-paths == nil")
    (= 0 (count tree-paths)) (log/info print-prefix "no tree paths to print"))
  (doseq [tree-path tree-paths]
    (log/info print-prefix (.getPath tree-path))
    (doseq [path-part (.getPath tree-path)]
      (log/info "    part:" path-part))))

(defn- scroll-path-to-visible [tree-component state]
  (let [tree-scroll-path (:tree-scroll-path state)]
    (when tree-scroll-path
      (.scrollPathToVisible tree-component (TreePath. ^"[Ljava.lang.Object;" (into-array Object tree-scroll-path))))))

(defn- create-component-listener [tree-model]
  (proxy [ComponentAdapter] []
    (componentResized [component-event]
      #_(log/info "JTree component resized")
      (let [state @(.state tree-model)
            tree-component (:tree-component state)]
        (scroll-path-to-visible tree-component state)))))

(defn- create-hierarchy-listener [tree-model]
  (proxy [Object HierarchyListener] []
    (hierarchyChanged [hierarchy-event]
      (when-not (zero? (bit-and HierarchyEvent/PARENT_CHANGED (.getChangeFlags hierarchy-event)))
       (let [state @(.state tree-model)
             tree-component (:tree-component state)
             parent (.getChangedParent hierarchy-event)
             component-listener (:component-listener state)]
          (if (nil? (.getParent tree-component))
            (.removeComponentListener parent component-listener)
            (.addComponentListener parent component-listener)))))))

(defn- tree-model-watch
  [this _key _ref old-value new-value]
  (let [old-tree (:tree old-value)
        new-tree (:tree new-value)
        old-tree-selection (:tree-selection old-value)
        new-tree-selection (:tree-selection new-value)
        old-tree-scroll-path (:tree-scroll-path old-value)
        new-tree-scroll-path (:tree-scroll-path new-value)
        tree-component (:tree-component new-value)
        listeners (:listeners new-value)]
    (when (not (SwingUtilities/isEventDispatchThread))
      (log/error (RuntimeException. "RTreeModel method called off EDT.")
                 (str "RTreeModel method called off EDT. RTreeModel methods should be called on EDT. "
                      "Continuing, but behavior may not be correct.")))
    (when (not= old-tree new-tree)
      (let [tree-model-event (TreeModelEvent. this (object-array [(:tree-root new-value)]))]
        (doseq [listener listeners]
          (.treeStructureChanged listener tree-model-event))))
    (when (not= old-tree-selection new-tree-selection)
      (let [tree-paths (mapv (fn [path]
                               (when (nil? path) (log/warn "jtree creating TreePath with nil object"))
                               (when (some nil? path) (log/warn "some element of path is nil:" path))
                               (TreePath. ^"[Ljava.lang.Object;" (into-array Object path)))
                             new-tree-selection)]
        (when (some nil? tree-paths) (log/warn "some tree path is nil"))
        (.setSelectionPaths tree-component (into-array TreePath tree-paths))))
    (when (not= old-tree-scroll-path new-tree-scroll-path)
      (scroll-path-to-visible tree-component new-value))))

(defn rtree-model-init-state []
  (let [tree-root (str (UUID/randomUUID))
        state (atom {:data   []
                     ; map of nodes to children
                     :tree   {tree-root []}
                     :tree-root tree-root
                     :tree-selection nil
                     :tree-model-fn default-tree-model-fn
                     :tree-render-fn identity
                     :listeners #{}})]
    #_(add-watch state :tree-model-self-watch tree-model-watch)
    [[] state]))

(defn rtree-model-post-init [this & _]
  (add-watch (.state this) :tree-model-self-watch (partial tree-model-watch this)) )

(defn- update-tree-selection [{:keys [data tree tree-root tree-selection tree-selection-fn] :as state}]
  (if tree-selection-fn
    (assoc state :tree-selection (tree-selection-fn tree-root tree data))
    (dissoc state :tree-selection)))

(defn- update-tree-scroll-path [{:keys [data tree tree-root tree-scroll-path-fn] :as state}]
  (if tree-scroll-path-fn
    (assoc state :tree-scroll-path (tree-scroll-path-fn tree-root tree data))
    (dissoc state :tree-scroll-path)))

(defn- update-tree
  [state]
  (->
    (let [data (:data state)
          tree-model-fn (:tree-model-fn state)
          [tree-root tree] (tree-model-fn (:tree-root state) data)]
      (assoc state :tree tree
                   :tree-root tree-root))
    (update-tree-selection)
    (update-tree-scroll-path)))

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

(defn rtree-model-setSelectionFn
  [this selection-fn]
  (swap! (.state this) assoc :tree-selection-fn selection-fn))

(defn rtree-model-setScrollPathFn
  [this scroll-path-fn]
  (swap! (.state this) assoc :tree-scroll-path-fn scroll-path-fn))

(defn rtree-model-setTreeComponent
  [this tree-component]
  (let [state @(.state this)
        old-tree-component (:tree-component state)
        component-listener (get state :component-listener (create-component-listener this))
        hierarchy-listener (get state :hierarchy-listener (create-hierarchy-listener this))]
    (when old-tree-component
      (.removeHierarchyListener old-tree-component hierarchy-listener))
    (log/info "adding component listener for JTree")
    (.addHierarchyListener tree-component hierarchy-listener)
    (swap! (.state this) assoc
           :tree-component tree-component
           :component-listener component-listener
           :hierarchy-listener hierarchy-listener)))

(defn- set-icon [cell-renderer icon]
  (.setClosedIcon cell-renderer icon)
  (.setOpenIcon cell-renderer icon)
  (.setLeafIcon cell-renderer icon))

(defn rtree-cell-renderer-getTreeCellRendererComponent
  [this tree value selected expanded leaf row has-focus]
  (let [tree-render-fn (:tree-render-fn @(.state (.getModel tree)))
        rendered-value (if tree-render-fn (tree-render-fn value) value)]
    (cond
      (string? rendered-value)
      (.superGetTreeCellRendererComponent this tree rendered-value selected expanded leaf row has-focus)
      (map? rendered-value)
      (do
          (if (contains? rendered-value :icon)
            (set-icon this (:icon rendered-value))
            (set-icon this nil))
          (.superGetTreeCellRendererComponent this tree (:value rendered-value) selected expanded leaf row has-focus))
      :else
      (do
        (log/warn "rtree-cell-renderer got unrecognized type " (type rendered-value) " with value " rendered-value)
        (.superGetTreeCellRendererComponent this tree "<unrecognized>" selected expanded leaf row has-focus)))))
