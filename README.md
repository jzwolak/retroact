# Retroact
[![GitHub license](https://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/jzwolak/retroact/blob/main/LICENSE)
[![Clojars Project](https://img.shields.io/clojars/v/com.insilicalabs/retroact.svg)](https://clojars.org/com.insilicalabs/retroact)

An experiment in creating a React like library in Clojure for Clojure, Java, and Swing.

The goal is to make something that can be used in Java and with legacy applications and have it be completely
Functional Reactive Programming.

This is a pure Clojure implementation and no Java bindings. There may be a future version with a Java API.

## Unique Contribution

Retroact has a couple unique contributions to the software developer community.

1. Retroact is not tied to Swing and was designed from the start to have a modular connection to the underlying toolkit.
   Therefore, it is easy to add support for other toolkits such as JavaFX, SWT, AWT, etc..
2. Retroact plays nice with existing legacy application code. The whole point of this project was to allow me to move
   my working forward into modern programming practices without requiring my clients to pay for a complete rewrite of
   their existing applications. Hence it is named "Retro"-act.

## The Future

1. Access control of state is planned to provide a better way to limit what components may access what state. The idea
   is to provide something transparent to components as they access the state and optional to components when they
   define new nodes/paths in the state tree. This is in contrast to the very limited, sometimes awkward, and sometimes
   incorrect way access control is handled in programs through OOP encapsulation and procedural scoping.
2. I would like to add JavaFX support using the modular interface for toolkit support and factor out Swing support into
   another project (say "retroact.swing"). I have no intentions of personally implementing support for other toolkits,
   but I hope others will and Retroact should fully support this.
3. Support for custom view languages. The view language is the result of calling the render fn and currently is a
   native Retroact syntax with keywords derived from the specifics of the underlying toolkit modular interface. This
   language is verbose and not the most fluent to read, though it is readable. It's possible to have a translation layer
   for arbitrary view languages to Retroact's native language in Retroact's main loop. In doing so, the components and
   render fns may work with the higher level view language, and possibly pluggable view languages of the user's
   choosing.

# Quick Start

Here's how to create your own basic app.

Alternatively, see [Run](#run) on how to run an example included with Retroact.

The following is a basic "Hello World" app that displays a Swing JFrame with the message "Hello World!".

```clojure
(defn hello-world-app
  []
  {:component-did-mount
   (fn component-did-mount [onscreen-component app-ref app-value]
     (.pack onscreen-component)
     (.setVisible onscreen-component true))
   :render
   (fn render [app-ref app-value]
     {:class      :frame
      :on-close   :dispose
      :contents   [{:class :label :text "Hello World!"}]
      })})
```

Note that there is no reference to Retroact. The app is simply a collection of fns and data. The fns are used by
Retroact at different lifecycle stages of the component. This app is a single component app - the app and component are
essentially one and the same here. The data (e.g., ":class :frame") is used by Retroact in conjunction with the toolkit
interface file. Each toolkit may define different keywords to map to its classes and attributes. Here, ":frame" maps to
a "JFrame", but it could very well be ":jframe" or ":foo". It depends on the author of the interface file.

Since there is no reference to Retroact, you must be wondering how to run this application. If using the repl, you could
run it like this

    > (require '[retroact.core :as r])
    > (r/init-app (hello-world-app))

Notice that `hello-world-app` is a fn. This is useful to prevent evaluation of any values in the map until it's time to
actually run the map. It's also useful in case something in the map should be dynamic and depend on a fn argument.
However, there is nothing to prevent you from defining the map statically using `def` or some other means. Please
remember that the contents of this map are to be treated as a value (that is, immutable). There are a few exceptions
to this, but that's for another discussion. Consider the contents immutable, only put immutable things in there, and if
something is mutable that you wish to be in there, don't mutate it.


# Run

Run the REPL then execute the following.

    > (require '[retroact.core :refer :all] :reload-all)
    > (require '[examples.greeter :refer :all] :reload-all)
    > (init-app (greeter-app))
