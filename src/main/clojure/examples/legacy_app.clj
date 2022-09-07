(ns examples.legacy-app
  (:require [retroact.core :as ra])
  (:import (java.awt BorderLayout)
           (javax.swing JFrame JLabel)))

(defn create-legacy-app []
  (let [frame (JFrame.)]
    (.setLayout frame (BorderLayout.))
    (.add frame (JLabel. "Legacy App") BorderLayout/NORTH)
    (.setDefaultCloseOperation frame JFrame/DISPOSE_ON_CLOSE)
    (.pack frame)
    (.setVisible frame true)
    frame))

(defn create-retroact-app []
  {:constructor         (fn [comp-id props state]
                          (assoc state comp-id props))
   :component-did-mount (fn [onscreen-component comp-id app-ref app-val]
                          (let [legacy-frame (get-in app-val [:state comp-id :legacy-frame])
                                location (get-in app-val [:state comp-id :location] BorderLayout/CENTER)]
                            (.add legacy-frame onscreen-component location)
                            (.pack legacy-frame)))
   :render              (fn [comp-id app-ref app-val]
                          {:class :label
                           :text  (get-in app-val [:state comp-id :msg] "Hello from Retroact")})})

(defn main []
  (let [legacy-frame (create-legacy-app)]
    (ra/init-app (create-retroact-app) {:legacy-frame legacy-frame})
    ; Alternatively, when creating many components throughout the lifecycle of the app, the following pattern may be
    ; better suited.
    #_(let [app-ref (ra/init-app)]
      (ra/create-comp app-ref (create-retroact-app) {:legacy-frame legacy-frame :location BorderLayout/CENTER :msg "hello"})
      (ra/create-comp app-ref (create-retroact-app) {:legacy-frame legacy-frame :location BorderLayout/SOUTH :msg "from down under"}))
    ))
