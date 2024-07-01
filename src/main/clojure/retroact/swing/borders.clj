(ns retroact.swing.borders
  (:require [retroact.swing.create-fns :refer [create-color create-font]])
  (:import (javax.swing BorderFactory Icon)
           (javax.swing.border Border EmptyBorder)))

(defn create-empty-border [{:keys [view]}]
  (if (contains? view :insets)
    (let [[top left bottom right] (:insets view)]
      (EmptyBorder. top left bottom right))
    (EmptyBorder. 0 0 0 0)))

(defn- create-border
  [border-spec]
  (let [border-spec (if (vector? border-spec) border-spec [border-spec])
        [type-or-border arg1 arg2 arg3 arg4 arg5 arg6] border-spec]
    (condp = [type-or-border (dec (count border-spec))]
      [:bevel 1] (BorderFactory/createBevelBorder arg1)
      [:bevel 3] (BorderFactory/createBevelBorder arg1 (create-color arg2) (create-color arg3))
      [:bevel 5] (BorderFactory/createBevelBorder arg1 (create-color arg2) (create-color arg3) (create-color arg4) (create-color arg5))
      [:compound 0] (BorderFactory/createCompoundBorder)
      [:compound 2] (BorderFactory/createCompoundBorder (create-border arg1) (create-border arg2))
      [:dashed 1] (BorderFactory/createDashedBorder (create-color arg1))
      [:dashed 3] (BorderFactory/createDashedBorder (create-color arg1) arg2 arg3)
      [:dashed 5] (BorderFactory/createDashedBorder (create-color arg1) arg2 arg3 arg4 arg5)
      [:empty 0] (BorderFactory/createEmptyBorder)
      [:empty 4] (BorderFactory/createEmptyBorder arg1 arg2 arg3 arg4)
      [:etched 0] (BorderFactory/createEtchedBorder)
      [:etched 1] (BorderFactory/createEtchedBorder arg1)
      [:etched 2] (BorderFactory/createEtchedBorder (create-color arg1) (create-color arg2))
      [:etched 3] (BorderFactory/createEtchedBorder arg1 (create-color arg2) (create-color arg3))
      [:line 1] (BorderFactory/createLineBorder (create-color arg1))
      [:line 2] (BorderFactory/createLineBorder (create-color arg1) arg2)
      [:line 3] (BorderFactory/createLineBorder (create-color arg1) arg2 arg3)
      [:lowered-bevel 0] (BorderFactory/createLoweredBevelBorder)
      [:lowered-soft-bevel 0] (BorderFactory/createLoweredSoftBevelBorder)
      [:matte 5] (BorderFactory/createMatteBorder arg1 arg2 arg3 arg4 (if (instance? Icon arg5) arg5 (create-color arg5)))
      [:raise-bevel 0] (BorderFactory/createRaisedBevelBorder)
      [:raise-soft-bevel 0] (BorderFactory/createRaisedSoftBevelBorder)
      [:soft-bevel 1] (BorderFactory/createSoftBevelBorder arg1)
      [:soft-bevel 3] (BorderFactory/createSoftBevelBorder arg1 (create-color arg2) (create-color arg3))
      [:titled 1] (BorderFactory/createTitledBorder (if (instance? String arg1) arg1 (create-border arg1)))
      [:titled 2] (BorderFactory/createTitledBorder (create-border arg1) arg2)
      [:titled 4] (BorderFactory/createTitledBorder (create-border arg1) arg2 arg3 arg4)
      [:titled 5] (BorderFactory/createTitledBorder (create-border arg1) arg2 arg3 arg4 (create-font arg5))
      [:titled 6] (BorderFactory/createTitledBorder (create-border arg1) arg2 arg3 arg4 (create-font arg5) (create-color arg6))
      (if (instance? Border type-or-border)
        type-or-border
        (throw (IllegalArgumentException.
                 (str "expected vector, keyword, or Border and got " (class type-or-border))))))))

(defn- set-border-by-fn
  [c ctx border set-border-fn]
  (cond
    (keyword? border) (set-border-fn c (create-border [border]))
    (vector? border) (set-border-fn c (create-border border))
    (instance? Border border) (set-border-fn c border)
    :else (throw (Exception. (str "provided attr val is neither a Border nor a vector: " border)))))

(defn set-border
  "If supplied border is a Border instance, use it. Otherwise, it should be a keyword or a vector. A keyword is used
   for a border with no arguments and a vector is used for a border that needs arguments to construct it. The first
   element of the vector is the same keyword as would be used for the no-arg version. The keywords are derived from
   the method names of the BorderFactory For example, createBevelBorder -> :bevel,
   createLoweredSoftBevelBorder -> :lowered-soft-bevel, createTitledBorder -> :titled, etc.. The remaining elements
   in the vector are args to the respective BorderFactory method."
  [c ctx border]
  (set-border-by-fn c ctx border (fn [c border] (.setBorder c border))))

(defn set-viewport-border
  [c ctx border]
  (set-border-by-fn c ctx border (fn [c border] (.setViewportBorder c border))))