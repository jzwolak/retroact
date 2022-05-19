# Swing React

See Evernote with the same name.

An experiment in creating a React like library in Clojure for Clojure, Java, and Swing.

The goal is to make something that can be used in Java and with legacy applications and have it be completely
Functional Reactive Programming.

I'm starting with a pure Clojure implementation and no Java bindings because that's easiest.

# Run

Run the REPL then execute the following.

    > (require '[swing-react.core :refer :all] :reload-all)
    > (require '[examples.greeter :refer :all] :reload-all)
    > (run-app (greeter-app))

# Left Off

I just finished making the atom contain Swing React data so that we can have a root component, virtual DOM, and other
stuff.

Next: write the attribute applier for contents which will detect object identity and not
recreate existing objects, but instead call the appliers on existing child components.

# See Also

pdb-reactive is another experimental project I started prior to this. I called the React like framework in that project
"retro-act". I like that name. It is less advanced than Swing React, but has some ideas worth considering, like the
structure of the map for the view (which is actually a vector at the root, which makes order important). It lacks
orthogonality, but is more readable and terse. I think the goal for Swing React is to have orthogonality and super clear
structure and conventions so that generating the structure and programmatically reading it is easy. Later, more fluent
DSLs can be written on top of the more orthogonal underlying structure... which is also pluggable. Another idea I got
was to use the identity as the key in the map (or tuple?). In this way, the identity can be a map itself with multiple
key-value pairs like the class name, a React like "key" and other things.
