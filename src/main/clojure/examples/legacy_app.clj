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

(defn create-retroact-app [& {:keys [location msg]
                              :or {location BorderLayout/CENTER
                                   msg "Hello from Retroact."}}]
  {:constructor         (fn [props state]
                          (merge state props))
   :component-did-mount (fn [onscreen-component app-ref app-val]
                          (let [legacy-frame (get-in app-val [:state :legacy-frame])]
                            (.add legacy-frame onscreen-component location)
                            (.pack legacy-frame)))
   :render              (fn [app-ref app-val]
                          {:class :label
                           :text  msg})})

(defn main []
  (let [legacy-frame (create-legacy-app)]
    (ra/init-app (create-retroact-app) {:legacy-frame legacy-frame})
    ; Alternatively, when creating many components throughout the lifecycle of the app, the following pattern may be
    ; better suited.
    #_(let [app-ref (ra/init-app)]
      (ra/create-comp app-ref (create-retroact-app :location BorderLayout/CENTER :msg "hello") {:legacy-frame legacy-frame})
      (ra/create-comp app-ref (create-retroact-app :location BorderLayout/SOUTH :msg "from down under") {:legacy-frame legacy-frame}))
    ))
