(ns retroact.helpers)

(defn- const-form [sym expr]
  `(do (defonce ~sym ~expr) ~sym))

(defmacro const-named
  "Where `const` generates a random symbol name, `const-named` takes the symbol name as an argument in `name`. This is
  useful for debugging and stack traces."
  [name expr]
  (const-form name expr))

(defmacro const
  "Create an inline constant that will return the exact reference/value every time. This is useful in render fns that
  call object constructors such as `(Color. 100 100 100)`. When placing this code inside `const`, it will become a
  constant such that `=` will return true. `(defn get-const [] (const (Color. 100 100 100)))` makes
  `(= (get-const) (get-const))` true."
  [expr]
  (const-form (gensym "const-") expr))
