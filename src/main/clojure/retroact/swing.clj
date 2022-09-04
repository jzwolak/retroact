(ns retroact.swing
  (:require [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :as log]
            [retroact.jlist :refer [create-jlist]])
  (:import (java.awt Color Component Container Dimension)
           (java.awt.event ActionListener)
           (javax.swing JButton JCheckBox JFrame JLabel JList JPanel JTextField)
           (javax.swing.event DocumentListener)
           (net.miginfocom.swing MigLayout)))

(def on-close-action-map
  {:dispose JFrame/DISPOSE_ON_CLOSE
   :do-nothing JFrame/DO_NOTHING_ON_CLOSE
   :exit JFrame/EXIT_ON_CLOSE
   :hide JFrame/HIDE_ON_CLOSE})

(defn reify-action-listener [action-handler]
  (reify ActionListener
    (actionPerformed [this action-event]
      (action-handler action-event))))

(defn reify-document-listener-to-text-change-listener [text-change-handler]
  (reify DocumentListener
    (changedUpdate [this document-event] (text-change-handler document-event))
    (insertUpdate [this document-event] (text-change-handler document-event))
    (removeUpdate [this document-event] (text-change-handler document-event))))

(defn set-width [c ctx width]
  (let [height (-> c .getSize .getHeight)]
    (.setSize c (Dimension. width height))))

(defn set-height [c ctx height]
  (let [width (-> c .getSize .getWidth)]
    (.setSize c (Dimension. width height))))


; :contents fns
(defmulti get-existing-children class)
; TODO: .getContentPane should probably only be called for Window (JFrame) components, not JPanel and Container
; components
(defmethod get-existing-children Container [c] (.getComponents (.getContentPane c)))
(defmethod get-existing-children JList [jlist]
  (let [model (.getModel jlist)]
    (println "JList get-existing-children not implemented")
    (-> model
        (.elements)
        (enumeration-seq)
        (vec))))

(defmulti add-new-child-at (fn [container child _ _] (class container)))
(defmethod add-new-child-at Container [^Container c ^Component child constraints index] (.add ^Container (.getContentPane c) child constraints ^int index))
(defmethod add-new-child-at JList [^JList jlist ^Component child constraints index]
  (let [model (.getModel jlist)]
    (println "jlist add-new-child-at" index "(model class:" (class model) " size =" (.getSize model) ")" child)
    (.add model index child)
    child))

(defmulti remove-child-at (fn [container index] (class container)))
(defmethod remove-child-at Container [c index] (.remove (.getContentPane c) index))
(defmethod remove-child-at JList [jlist index] (println "JList remove-child-at not implemented yet"))

(defmulti get-child-at (fn [container index] (class container)))
(defmethod get-child-at Container [c index] (.getComponent (.getContentPane c) index))
(defmethod get-child-at JList [jlist index]
  (let [child
        (-> jlist
            (.getModel)
            (.getElementAt index))]
    (println "jlist get-child-at:" child)
    child))
; end :contents fns


; TODO: oops... I just added all the :class key-value pairs, but perhaps unnecessarily. I did that so I could match
; the children to the virtual dom, but I don't need to do that. The diff will be between two virtual doms. After the
; diff is complete I should have a list of deletions, insertions, and changes (apply attributes) at particular indices.
; I won't need to look at the class or identity of the actual components, I can just remove the necessary indices, add
; the necessary indices, and update attributes.
(def class-map
  {:default    (fn default-swing-component-constructor []
                 (log/warn "using default constructor to generatoe a JPanel")
                 (JPanel.))
   :button     #(JButton.)
   :frame      #(JFrame.)
   :label      #(JLabel.)
   :check-box  #(JCheckBox.)
   :text-field #(JTextField.)
   :list       create-jlist
   :mig-layout #(MigLayout.)}
  #_{:frame      {:constructor #(JFrame.) :class JFrame}
     :label      {:constructor #(JLabel.) :class JLabel}
     :mig-layout {:constructor #(MigLayout.) :class MigLayout}
     :button     {:constructor #(JButton.) :class JButton}})

(defn text-changed? [old-text new-text]
  (not
    (or (= old-text new-text)
        ; empty string and nil are treated the same. See JTextComponent.setText().
        (and (nil? old-text) (empty? new-text))
        (and (empty? old-text) (nil? new-text)))))

(def attr-appliers
  {:background         (fn set-background [c ctx color] (cond
                                                          (instance? JFrame c) (.setBackground (.getContentPane c) (Color. color))
                                                          :else (.setBackground c (Color. color))))
   :border             (fn set-border [c ctx border] (.setBorder c border))
   ; The following doesn't appear to work. It may be that macOS overrides margins.
   :margin             (fn set-margin [c ctx insets] (.setMargin c insets))
   :height             set-height
   :layout             {:set
                        (fn set-layout [c ctx layout]
                          (pprint ctx)
                          #_(get-in ctx [])
                          (println "old layout:"
                                   (try (.getLayout (.getContentPane c))
                                        (catch NullPointerException e "none")))
                          (println "new layout:" layout)
                          ; TODO: build-ui should not be called here. Instead, layout should be built in the caller...
                          ; That is, Retroact should build the layout component from the data before calling this
                          ; attr-applier. See comments and TODO items in retroact.core "How to resolve arguments".
                          #_(let [layout-component (build-ui (:app-ref ctx) layout)]
                              (println "layout component:" layout-component)
                              (println "setting layout for:" c)
                              (.setLayout c layout-component))
                          (.setLayout c layout)
                          (println "layout set:" (.getLayout (.getContentPane c))))
                        :get (fn get-layout [c ctx] (.getLayout (.getContentPane c)))}
   :opaque             (fn set-opaque [c ctx opaque] (cond
                                                       (instance? JFrame c) (.setOpaque (.getContentPane c) opaque)
                                                       :else (.setOpaque c opaque)))
   :text               (fn set-text [c ctx text]
                         #_(.printStackTrace (Exception. "stack trace"))
                         (let [old-text (.getText c)]
                           (println (str "new-text = \"" text "\" old-text = \"" old-text "\""))
                           (println (str "new-text nil? " (nil? text) " old-text nil? " (nil? old-text)))
                           (when (text-changed? old-text text)
                             (.setText c text))))
   :width              set-width
   :caret-position     (fn set-caret-position [c ctx position] (.setCaretPosition c position))
   ; TODO: if action not in on-close-action-map, then add it as a WindowListener to the close event
   :on-close           (fn on-close [c ctx action] (.setDefaultCloseOperation c (on-close-action-map action)))
   :layout-constraints (fn set-layout-constraints [c ctx constraints] (.setLayoutConstraints c constraints))
   :row-constraints    (fn set-row-constraints [c ctx constraints] (.setRowConstraints c constraints))
   :column-constraints (fn set-column-constraints [c ctx constraints] (.setColumnConstraints c constraints))
   ; All action listeners must be removed before adding the new one to avoid re-adding the same anonymous fn.
   :on-action          (fn on-action [c ctx action-handler]
                         (doseq [al (vec (.getActionListeners c))] (.removeActionListener c al))
                         (.addActionListener c (reify-action-listener (fn action-handler-clojure [action-event]
                                                                        (action-handler (:app-ref ctx) action-event)))))
   :on-text-change     (fn on-text-change [c ctx text-change-handler]
                         #_(doseq [dl (vec (-> c .getDocument .getDocumentListeners))]
                             (when (instance? DocumentListener dl) (.removeDocumentListener (.getDocument c) dl)))
                         (.addDocumentListener (.getDocument c)
                                               (reify-document-listener-to-text-change-listener
                                                 (fn text-change-handler-clojure [doc-event]
                                                   (text-change-handler (:app-ref ctx) (.getText c))))))
   ; TODO: refactor add-contents to a independent defn and check component type to be sure it's a valid container.
   ;  Perhaps pass in the map in addition to the component so that we don't have to use `instanceof`?
   ; TODO:
   ; - specify getter for existing child components
   ; - specify fn for adding new child component at specified index
   ; - no need to specify how to update a child component... that is just as if it was a root component.
   ; - no need to specify how to create a child component... that is also as if it was a root component.
   :contents           {:get-existing-children get-existing-children #_(fn get-existing-children [c] (.getComponents (.getContentPane c)))
                        :add-new-child-at      add-new-child-at #_(fn add-new-child-at [^Container c ^Component child constraints index] (.add ^Container (.getContentPane c) child constraints ^int index))
                        :remove-child-at       remove-child-at #_(fn remove-child-at [c index] (.remove (.getContentPane c) index))
                        :get-child-at          get-child-at #_(fn get-child-at [c index] (.getComponent (.getContentPane c) index))}
   })
