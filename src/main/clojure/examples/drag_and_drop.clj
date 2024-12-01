(ns examples.drag-and-drop
  (:require [clojure.tools.logging :as log]
            [retroact.swing.util :refer [get-view]])
  (:import (java.awt BorderLayout Color)
           (java.awt.datatransfer StringSelection)
           (javax.swing TransferHandler)))

; From REPL, do
; > (require '[retroact.core :as r])
; > (require '[examples.drag-and-drop :as dnd])
; > (r/init-app-ref dnd/app-state)
; > (r/create-comp dnd/app-state (dnd/drag-and-drop-app))

(def app-state (atom {:list [{:id 1
                              :text "one"}
                             {:id 2
                              :text "two"}
                             {:id 3
                              :text "three"}]}))


(def transfer-handler
  (delay (proxy [TransferHandler] []
           (getSourceActions [_] TransferHandler/COPY)
           (createTransferable [jlist]
             (let [selected-index (.getSelectedIndex jlist)
                   view (get-view jlist)
                   data (:data view)]
               (StringSelection. (:text (nth data selected-index))))))))

(defn- render-list-contents [app-val]
  (mapv (fn render-list-item [item] {:class :label
                                     :opaque true           ; labels are not opaque by default which can mess up colors when embedded in lists.
                                     :id    (:id item)
                                     :text  (:text item)})
        (:list app-val)))

(defn drag-and-drop-app []
  {:component-did-mount (fn dnd-component-did-mount [onscreen-component app-ref app-val]
                          (.pack onscreen-component)
                          (.setVisible onscreen-component true))
   :render (fn dnd-render [app-ref app-val]
             {:class    :frame
              :on-close :dispose
              :layout   {:class :border-layout}
              :color    Color/RED
              :contents [{:class            :list
                          :constraints      BorderLayout/WEST
                          :drag-enabled     true
                          :transfer-handler @transfer-handler
                          :data             (:list app-val)
                          :contents         (render-list-contents app-val)}
                         {:class       :text-area
                          :constraints BorderLayout/CENTER}]})})
