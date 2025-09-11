(ns retroact.swing.jtree
  (:require [clojure.tools.logging :as log])
  (:import (javax.swing JScrollPane JTree)
           (javax.swing.tree TreeModel)
           (retroact.swing.compiled.jtree RTreeCellRenderer RTreeModel)))


;; -----------------------------------------------------------------------------
;; retroact.swing.jtree — public attribute API for :tree components
;; -----------------------------------------------------------------------------
;; This namespace exposes the attribute appliers for using a JTree via Retroact.
;; In your component spec you typically write something like (note: no anonymous
;; fns because they will cause unnecessary rerenders):
;;   (defn tree-model-fn [tree-root data] [new-root tree-map])
;;   (defn tree-render-fn [node] <string-or-{:value string :icon Icon}>)
;;   (defn tree-selection-fn [tree-root tree data] <vector-of-paths>)
;;   (defn tree-scroll-path-fn [tree-root tree data] <single-path>)
;;   {:class :tree
;;    :tree-data        data-vector
;;    :tree-model-fn    tree-model-fn
;;    :tree-render-fn   tree-render-fn
;;    :tree-selection-fn  tree-selection-fn
;;    :tree-scroll-path-fn tree-scroll-path-fn
;;    :toggle-click-count 2}
;;
;; Attribute semantics (high level)
;; - :tree-data
;;   Raw application data (a vector or any collection) used as the input to
;;   :tree-model-fn. Changing this triggers the model to rebuild the tree.
;;   This is sometimes referred to as just data (or :data) for fns that take it.
;;   It may contain more than just the data for tree nodes. It may contain the
;;   selection, scroll path, a search filter, or anything else. Note that
;;   Retroact doesn't do anything direct with this data, it simply passes it
;;   on to the various fns supplied by the app (like tree-model-fn and
;;   tree-selection-fn).
;;
;; - :tree-model-fn
;;   Fn of two args: (fn [tree-root data] [new-tree-root tree]). Must return a pair
;;   where `tree` is a map of parent -> vector of child nodes (arbitrary values).
;;   The nodes you put in that structure are what JTree navigates and what is passed
;;   to :tree-render-fn. The default model function is very simple: it creates a
;;   root and nests `data` underneath it.
;;
;; - :tree-render-fn
;;   Fn of one arg: (fn [node] ...). Should return either:
;;     * a String (used directly as the cell text), or
;;     * a map {:value <string> :icon <javax.swing.Icon>} to customize both text and
;;       icon. Any other return types will render as "<unrecognized>" and log a warn.
;;
;; - :tree-selection-fn
;;   Fn of three args: (fn [tree-root tree data] <paths>). Should return a vector of
;;   TreePaths, each TreePath expressed as a Clojure vector listing the node values
;;   from root to the target node, e.g. [root child grandchild]. When this value
;;   changes, the JTree selection is updated via setSelectionPaths.
;;
;; - :tree-scroll-path-fn
;;   Fn of three args: (fn [tree-root tree data]). Should return a single path vector
;;   (same format as above). When it changes, the JTree scrolls that path into view.
;;
;; - :toggle-click-count
;;   Integer passed to JTree.setToggleClickCount. Controls how many clicks expand /
;;   collapse (e.g., 1 or 2). This is set directly on the JTree component.
;;
;; Notes
;; - Attribute appliers in this ns accept either a JTree or a JScrollPane containing
;;   a JTree; helper fns locate the underlying JTree or TreeModel as needed.
;; - Model-level semantics (how :tree-data, :tree-model-fn, selection, scroll-paths
;;   interact, and the TreeModel contract) are implemented by
;;   retroact.swing.compiled.jtree (see that file for deeper details).

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
  "Set a selection function for the tree model.
  tree-selection-fn: (fn [tree-root tree data] <vector-of-paths>) where each
  path is a vector of nodes from the root to the selected node.
  Example return: [[root a] [root b c]]."
  [c ctx tree-selection-fn]
  (safe-tree-model-set c (memfn setSelectionFn tree-selection-fn) tree-selection-fn))

(defn set-tree-scroll-path-fn
  "Set a function that computes a single path to scroll into view.
  tree-scroll-path-fn: (fn [tree-root tree data] <single-path>) where the path
  is a vector of nodes from the root down to the node to reveal."
  [c ctx tree-scroll-path-fn]
  (safe-tree-model-set c (memfn setScrollPathFn tree-scroll-path-fn) tree-scroll-path-fn))

(defn set-tree-model-fn
  "Set the model function used to derive the tree structure from :tree-data.
  tree-model-fn: (fn [tree-root data] [new-tree-root tree]) where `tree` is a
  map parent->vector-of-children. The nodes may be any values; they are passed
  to :tree-render-fn.
  Returning a different root replaces the root shown by the JTree."
  [c ctx tree-model-fn]
  (safe-tree-model-set c (memfn setModelFn tree-model-fn) tree-model-fn))

(defn set-tree-render-fn
  "Set the render function for nodes.
  tree-render-fn: (fn [node] <string | {:value string :icon Icon}>). If a map
  is returned, :value provides the label text and :icon (optional) is applied
  to the DefaultTreeCellRenderer.
  Any other return type will render as \"<unrecognized>\" and log a warning."
  [c ctx tree-render-fn]
  (safe-tree-model-set c (memfn setRenderFn tree-render-fn) tree-render-fn))

(defn set-tree-data
  "Set the raw application data vector or collection consumed by :tree-model-fn.
  Changing this will cause the model to rebuild the tree using the current
  :tree-model-fn and then update selection/scroll path according to the current
  :tree-selection-fn and :tree-scroll-path-fn."
  [c ctx data]
  (safe-tree-model-set c (memfn setData data) data))

(defn set-tree-toggle-click-count
  "Set the number of clicks to toggle expand/collapse on the JTree. Typical
  values are 1 or 2. Applied directly on the JTree component."
  [c ctx t-count]
  (safe-tree-set c (memfn setToggleClickCount t-count) t-count))

(defn create-jtree [ctx]
  (let [tree-model (RTreeModel.)
        tree (JTree. ^TreeModel tree-model)]
    (.setTreeComponent tree-model tree)
    (.setCellRenderer tree (RTreeCellRenderer.))
    (.setRootVisible tree false)
    (.setShowsRootHandles tree true)
    tree))
