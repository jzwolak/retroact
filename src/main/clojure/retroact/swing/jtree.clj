(ns retroact.swing.jtree
  (:require [clojure.tools.logging :as log])
  (:import (javax.swing JScrollPane JTree)
           (javax.swing.tree TreeModel)
           (retroact.swing.compiled.jtree RTreeCellRenderer RTreeModel)))

(defn safe-tree-set [tree f attribute]
  (cond
    (instance? JTree tree) (f tree attribute)
    (instance? JScrollPane tree) (safe-tree-set (-> tree (.getViewport) (.getView)) f attribute)
    :else (log/error "skipping fn on tree because component does not appear to be a tree: " tree)))

(defn safe-tree-model-set [tree f attribute]
  (safe-tree-set
    tree (fn set-on-tree-model [tree attr]
           (let [model (.getModel tree)]
             (f model attr)))
    attribute))

(defn set-tree-selection-fn
  [c ctx tree-selection-fn]
  (safe-tree-model-set c (memfn setSelectionFn tree-selection-fn) tree-selection-fn))

(defn set-tree-scroll-path-fn
  [c ctx tree-scroll-path-fn]
  (safe-tree-model-set c (memfn setScrollPathFn tree-scroll-path-fn) tree-scroll-path-fn))

(defn set-tree-model-fn
  [c ctx tree-model-fn]
  (safe-tree-model-set c (memfn setModelFn tree-model-fn) tree-model-fn))

(defn set-tree-render-fn
  [c ctx tree-render-fn]
  (safe-tree-model-set c (memfn setRenderFn tree-render-fn) tree-render-fn))

(defn set-tree-data
  [c ctx data]
  (safe-tree-model-set c (memfn setData data) data))

(defn set-tree-toggle-click-count
  [c ctx t-count]
  (safe-tree-set c (memfn setToggleClickCount t-count) t-count))

(defn create-jtree [ui]
  (let [tree-model (RTreeModel.)
        tree (JTree. ^TreeModel tree-model)]
    (.setTreeComponent tree-model tree)
    (.setCellRenderer tree (RTreeCellRenderer.))
    (.setRootVisible tree false)
    (.setShowsRootHandles tree true)
    (JScrollPane. tree)))
