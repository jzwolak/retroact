(ns retroact.toolkit.property-getters-setters)

(defn- get-default-by-fresh-instantiation [component getter]
  (let [new-instance (.newInstance (.getConstructor (class component) (into-array Class [])) (into-array Object []))
        default-value (getter new-instance)]
    default-value))

(defn set-property
  "Sets a property on a component that potentially has a ContentPane. If new-value is null, then the default will be
  used."
  [c new-value getter setter]
  (let [new-value (if (nil? new-value) (get-default-by-fresh-instantiation c getter) new-value)]
    (setter c new-value)))

