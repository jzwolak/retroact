(ns retroact.swing.menu-bar
  (:import (javax.swing JMenuBar)))

(defn- create-menu-bar []
  (JMenuBar.))

(defn- get-menu-bar [frame]
  (or (.getJMenuBar frame)
      (let [mb (create-menu-bar)]
        (.setJMenuBar frame mb)
        mb)))

(defn get-existing-children [frame]
  (if-let [menu-bar (.getJMenuBar frame)]
    (.getComponents menu-bar)
    []))

(defn add-new-child-at [frame menu view index]
  (-> (get-menu-bar frame)
      (.add menu index)))

(defn remove-child-at [frame index]
  (-> (.getJMenuBar frame)
      (.remove index)))

(defn get-child-at [frame index]
  (-> (.getJMenuBar frame)
      (.getComponent index)))
