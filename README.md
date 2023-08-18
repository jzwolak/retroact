# Retroact
[![GitHub license](https://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/jzwolak/retroact/blob/main/LICENSE)
[![Clojars Project](https://img.shields.io/clojars/v/com.insilicalabs/retroact.svg)](https://clojars.org/com.insilicalabs/retroact)

An experiment in creating a React like library in Clojure for Clojure, Java, and Swing.

The goal is to make something that can be used in Java and with legacy applications and have it be completely
Functional Reactive Programming (FRP).

This is a pure Clojure implementation and no Java bindings. There may be a future version with a Java API.

## Unique Contribution

Retroact has a couple unique contributions to the software developer community.

1. Retroact is not tied to Swing and was designed from the start to have a modular connection to the underlying toolkit.
   Therefore, it is easy to add support for other toolkits such as JavaFX, SWT, AWT, etc..
2. Retroact plays nice with existing legacy application code. The whole point of this project was to allow me to move
   my work forward into modern programming practices without requiring my clients to pay for a complete rewrite of
   their existing applications. Hence it is named "Retro"-act.

## The Future

1. Access control of state is planned to provide a better way to limit what components may access what state. The idea
   is to provide something transparent to components as they access the state and optional to components when they
   define new nodes/paths in the state tree. This is in contrast to the very limited, sometimes awkward, and sometimes
   incorrect way access control is handled in programs through OOP encapsulation and procedural scoping.
2. I would like to add JavaFX support using the modular interface for toolkit support and factor out Swing support into
   another project (say "retroact.swing"). I have no intentions of personally implementing support for toolkits other
   than Swing and JavaFX, but I hope others will and Retroact should fully support this.
3. Support for custom view languages. The view language is the result of calling the render fn and currently is a
   native Retroact syntax with keywords derived from the specifics of the underlying toolkit modular interface. This
   language is verbose and not the most fluent to read, though it is readable. It's possible to have a translation layer
   for arbitrary view languages to Retroact's native language in Retroact's main loop. In doing so, the components and
   render fns may work with the higher level view language, and possibly pluggable view languages of the user's
   choosing.


# Dependency

```gradle
dependencies {
    implementation 'com.insilicalabs:retroact:0.2.4'
}
```

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

Run the REPL then execute the following to run the greeter-app from the Retroact examples.

    > (require '[retroact.core :refer :all] :reload-all)
    > (require '[examples.greeter :refer :all] :reload-all)
    > (init-app (greeter-app))


# Usage

`init-app` only needs to be called once and may be called with a component. More components may be added later with
`create-comp`. Generally, components created with `create-comp` are somewhere close to the root if not the root and last
for the duration of the app. However, in some exceptional cases - like when transitioning a legacy code base - there
may be components that need to be created and destroyed. Components may be destroyed with `destroy-comp`.

* `init-app` - initialize Retroact and the current app. Maybe called multiple times if there are multiple apps in the
               current JVM.
* `create-comp` - create a component; generally the root component or component that lasts for the duration of the app.
                  Create as many components as you like, but there probably only needs to be one for a pure Retroact
                  app - the root. All other components are created dynamically by Retroact.
* `destroy-comp` - destroy a component; only used in rare cases like with legacy code.


## Legacy Apps

The whole point of Retroact is enable the transition of legacy code to a functional reactive programming model. Here are
some of the deeper ways in which Retroact supports this.

An existing complex component may have part of its state transitioned to Clojure state and updated using FRP. For this
example let's assume the component is created during application startup and is destroyed when the application is
shutdown (though Retroact supports a component life cycle different from the application runtime). For simplicity, let's
say the component is a subclass of `JFrame` and somewhere there is a line of Java code that looks like

    JFrame myComponent = createMyComponent();

Now this Java object may be saved in your definition of a Retroact component as follows (assuming you know how to get
a reference to this Java object in Clojure):

```clojure
(defn my-legacy-component [my-component]
    {:onscreen-component my-component
     :update-onscreen-component update-my-component
     :render
      (fn render-my-component [app-ref app-val]
          {:title (get-in app-val [:state :title])
           :color (get-in app-val [:state :color]})
    })

(my-legacy-component myComponent)
```

There are three important differences between this "legacy" component and a standard Retroact component.

1. The `:onscreen-component` is defined. Normally, Retroact manages the life cycle of the onscreen component. In this
   case we've supplied it and Retroact will use it and _not_ manage the life cycle of the onscreen component.
2. An updater fn is supplied in `:update-onscreen-component`. Retroact has a default updater fn, but when one is
   supplied Retroact will use the supplied fn instead. The supplied fn will be called _only_ when the result of the
   render fn has changed and the update fn must apply those updates to the onscreen component.
3. The render fn returns an arbitrary map that is only semantically meaningful to `update-my-component`. This map is not
   used in other parts of Retroact - unlike the maps for Swing components - except to compare equality to determine if
   the udpate fn should be called.

And so, here is the update fn.

```clojure
(defn update-my-component [{:keys [onscreen-component old-view new-view]}]
    (.setTitle onscreen-component (:title new-view))
    (.setBackground onscreen-component (:color new-view)))
```

In this case both attributes are set, but for efficiency and larger components it would be better to compare those
attributes in old-view and new-view to see which have actually changed. Also, it is a good practice to not change these
attributes anywhere else - as in, do not modify the title or background in the legacy code - and let Retroact manage
them. If the legacy code needs to update the title or background color then have it set the appropriate value in the
Clojure version of the application state and Retroact will automatically update the component!

You may be wondering about the mutability of `myComponent` and the earlier statement about only putting immutable data
in Retroact components. This is precisely the exception, but it's also important to note that Retroact is only seeing
the reference value and not the object value. Since the component is not changing, the reference value is not changing
and, as far as Retroact is concerned, the Retroact component is a _value_, though not in the strictest of senses.

## Handler Fns

Handler fns are better specified as named fns rather than anonymous fns.

```clojure
:on-action handler
```
is better than
```clojure
:on-action (fn [app-ref action-event] ...)
```

This is because the render fn is called every time something changes. An anonymous function will have a different name
and id each time and therefore Retroact will see it as a different fn. Retroact will then remove the old handler and add
the new one even if the actual code inside is identical.

In some cases the attribute may not properly remove the previous fn, though this would be considered a bug.


# Internals

When the app-ref (application state) changes, Retroact has a watch on that
ref, which responds by updating the view. The watch does so by enqueueing
a request to update the view on a channel specific to the app-ref. The chan
has a sliding buffer so that only the latest update is performed. Since the
view is a function of the state it does not matter what the previous value
of the state was, only the current value matters.

The old value from the watch is not transmitted to the update view code
because it may have no relevance since the 1 element sliding buffer of the
app-ref's chan will discard some values of app-ref before they ever get
rendered. Therefor, the value of app-ref, or more importantly the rendered
view, is saved with the onscreen component. This can then be retrieved as the
old value of the rendered view for comparison to see what has changed from
one view rendering to the next.
