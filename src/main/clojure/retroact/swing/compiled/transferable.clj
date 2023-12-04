(ns retroact.swing.compiled.transferable
  (:import (clojure.lang IPersistentMap)
           (java.awt.datatransfer DataFlavor UnsupportedFlavorException)))

(gen-class
  :name "retroact.swing.compiled.transferable.RTransferable"
  :implements [java.awt.datatransfer.Transferable]
  :state "state"
  :init "init"
  ;:post-init "post-init"
  :prefix "transfer-handler-"
  :constructors {[clojure.lang.IPersistentMap] []}
  ;:methods
  #_[[getTransferData [DataFlavor] Object]
   [getTransferDataFlavors [] "[Ljava.awt.datatransfer.DataFlavor;"]])

(defn- get-data-as-string [data]
  (str data))

(defn- get-data-as-map [data]
  data)

(def data-flavors (delay {DataFlavor/stringFlavor get-data-as-string
                          (DataFlavor. (str DataFlavor/javaJVMLocalObjectMimeType ";class=" (.getName IPersistentMap)))
                          get-data-as-map}))

(defn transfer-handler-init [data]
  [[] {:data data}])

(defn transfer-handler-getTransferData [this data-flavor]
  (when (not (.isDataFlavorSupported this data-flavor))
    (throw (UnsupportedFlavorException. data-flavor)))
  (let [data-getter (get @data-flavors data-flavor)
        state (.state this)]
    (data-getter (:data state))))

(defn transfer-handler-getTransferDataFlavors [this]
  (into-array DataFlavor (keys @data-flavors)))

(defn transfer-handler-isDataFlavorSupported [this data-flavor]
  (contains? @data-flavors data-flavor))
