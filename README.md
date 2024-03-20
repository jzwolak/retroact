# Retroact
[![GitHub license](https://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/jzwolak/retroact/blob/main/LICENSE)
[![Clojars Project](https://img.shields.io/clojars/v/com.insilicalabs/retroact.svg)](https://clojars.org/com.insilicalabs/retroact)

An experiment in creating a React like library in Clojure for Clojure, Java, and Swing.

The goal is to make something that can be used in Java and with legacy applications and have it be completely
Functional Reactive Programming (FRP). The user interface code is declarative, it is a function of the app state, and
the code that initializes and updates the UI is one.

This is a pure Clojure implementation and no Java bindings. There may be a future version with a Java API.

Though this is an experimental project, it is being used in a production rich internet client application for biological
modeling and simulation. Its status will likely be upgraded, as it has matured significantly since the above text was
written.

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
something is mutable that you wish to be in there, don't mutate it. That does not mean the result of calling the render
fn should return the same value, but those values should be immutable (true Clojure values) or Java Objects where you
pledge not to mutate their state.


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

## Extending

Retroact may be extended to support any custom classes (Swing or otherwise) or support Swing classes that are
not already supported. Use the `register-comp-type!` fn to register classes (as components) and attribute appliers
for those classes. Note that attribute appliers are always global and will work on _all_ components. This may be avoided
by specifically checking the instance type within the body of the attribute applier and executing the body only for
the desired instance types.

To register a component, somewhere in your startup code (before the component is used) run something like

```clojure
(register-comp-type! app-ref :my-comp MyComp {:my-attr my-attr-applier-fn})
```

`:my-comp` and `:my-attr` are arbitrary names for the keys you want to use. A rendered component view with these
attribute names will then look like

```clojure
{:class :my-comp
 :my-attr "my attribute value"}
 ```

`MyComp` is the class name of the component. Optionally a fn may be used that returns a new instance of the component.
Fns have the advantage of receiving the view (as seen above) and may take attributes or other information from that
view to use in the constructor. When a change in an attribute value requires recreating the instance then that must
be specified during the `register-comp-type!` call with

```clojure
(register-comp-type! app-ref :my-comp MyComp {:my-attr {:recreate [MyComp]}})
```

The above will always recreate `MyComp` instances when `:my-attr` changes. It will not affect other instance types. Add
more classes to the `:recreate` vector if you wish other instance types to be recreated also. Remember, the attribute
applier works on all instance types! Though, this will only affect components created using `app-ref` as the app state
in `create-comp`.

Here's an example that adds `JLabel`, which is already supported, but this is what it could look like.

```clojure
(register-comp-type! app-ref :name-label JLabel {:name-text (fn [c ctx name] (.setText c (str "Name: " name))])})
```

Be careful not to override existing attribute appliers. See `retroact.swing` for a list of appliers for Swing. One way
to reliably avoid this is to namespace your keys using `::` (two colons) or by explicity specifying the namespace. None
of the default Retroact keys are namespaced. Same for the class name key.

### Appliers In Depth

Attribute appliers, in their simplest form, are fns that know how to set the value of the attribute on the onscreen
component. There are many cases where a simple function won't do, therefore, attribute appliers may also be a map with
more details on how they should work.

There are three basic types of appliers: fn applier, component applier, and children applier.

* fn applier - assumes the attribute is a "simple" value like a string, integer, color, point, etc.. Note that some of
  these are objects, but they are small, easy to recreate, and usually immutable.
* component applier - assumes the attribute points to a heavier weight component that has attributes of its own. This
  is a nested component and will have a map in the rendered view to represent _its_ attributes. The attributes will be
  recursively applied and the component will not be recreated or discarded.
* children applier - assumes the attribute points to a list or array of components that must be managed. The rendered
  view will be a vector of maps where each map represents a component.

Here are some examples of the specification and usage of each type of applier.

```clojure
; specification
{:fn-applier           attr-setter-fn
 :fn-applier-alternate {:fn attr-setter2-fn}
 :component-applier    {:set set-component-fn
                        :get get-component-fn}
 :children-applier     {:get-child-at          get-child-at-fn
                        :add-new-child-at      add-new-child-at-fn
                        :get-existing-children get-existing-children-fn
                        :remove-child-at       remove-child-at-fn}}

; usage
(defn render-fn [app-ref app-val]
  {:class :my-comp
   :fn-applier "bar"
   :fn-applier-alternate "foo"
   :component-applier {:class :label :text "World"}
   :children-applier [{:class :label :text "Hello"}]})
```

See `retroact.swing` for complete examples of the implementations of the different types.

Each applier map may also contain `:recreate` and `:deps` for further functionality. A map with only `:recreate` will
only affect recreation of an instance when the attribute changes and will have no way to actually set the value of the
attribute on an existing instance. This is the case for some classes (`FileNameExtensionFilter` for instance).

`:deps` specifies the keys for other attribute appliers that must run before the current one. Like the `:selected-index`
applier for `:tabbed-pane` (`JTabbedPane`). It must run _after_ the `:contents` have been updated otherwise the selected
index may not exist.

#### Update On Change

Appliers are _not_ run unless the value returned by the render fn has changed. In particular, the _value of the
attribute_ must have changed since it was last set. There are some things to consider here. One is that the component
ought not be modified outside Retroact because Retroact will have no way to know the component state has changed. If the
component state is changed by the user (like the value of a `JTextField`) then you'll have to update that value in the
app state as the user changes it if that attribute applier is being used. If the `:text` attribute applier is not being
used then it's possible to just get the value of the `JTextField` as necessary (e.g., in a handler).

Infinite loops (updates) may occur if the app state is being updated from the onscreen component and the onscreen
component is being updated from the app state. One particularly difficult situation is when the value of the onscreen
component is behind the app state and an event is queued for updating the app state the same time an event is queued
to update the onscreen component from the app state. This can cause the state to bounce between two values. Usually,
these loops do not occur, but they may occur when the onscreen component state and the app state are being synced and
the onscreen component state may be directly modified by the user. In such cases, checking if a modification came from
the user (mouse or key event) can break the loop. Here's code I've used to do this check.

```clojure
(defn user-initiated? []
  (let [cause (EventQueue/getCurrentEvent)]
    (or (instance? MouseEvent cause) (instance? KeyEvent cause))))
```

This isn't foolproof, but it works in many cases.

## Side Effects

Quick example...
```{:side-effect (fn [app-ref old-val new-val] ...)}```

Example with render...
```
{:side-effect (fn [app-ref old-val new-val] ...)
 :render (fn [app-ref app-val] {:class :panel :contents [...]}}
```

Retroact has support for side effects, but be careful. Somethings should _not_ be side effects. Side effects are also
expensive. All side effects are called everytime the app-ref value is changed. Therefore, the first thing the side
effect should do is check that something changed to warrant it running. This would likely involve a change from old-val
to new-val for a specific path in the app-ref. A change should be looked for, not a specific value. Because later calls
to the side effect may be from a change in another path in app-ref. For something like sending an email, one may wish
to set a value in app-ref indicating the email was sent (successfully or with an error). In reality, handlers on buttons
can do this much better, but in some cases the side effect needs to be in response to a change in state and not a user
action. Just make sure the side effect fn _checks_ for the _change_ in state.

Here's a few examples of things that should be side effects:

* making a network call to send an email
* starting a long running task to calculate something complex
* making an asynchronous network call to load data

Some of these things may just be started inside a handler function and not need Retroact's side effect support.

Some things that are _not_ side effects:

* expanding a tree node after the user clicks a button
* updating a selection
* clearing text in a text field after a dialog "Ok" button is pressed

All of these should be encoded in the application state and the view should be rendered from that state as a function
of the state. So if the expansion of a tree node is to happen in response to the user clicking a button, then the
application should _update the application state_ in response to the user clicking a button. The view will then
automatically expand the tree node using the appropriate applier. The application will, of course, have to specify
the applier in the appropriate render fn and reference the application state that holds the tree node expansion values.
If the appropriate applier does not exist, then you may write one. They are easy enough to write and will maintain
the ease of a declarative view and the view-is-a-function-of-the-state paradigm. Everything will be easier this way.

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

# Tests

Tests can be run using Clojure's builtin testing using Gradle or the REPL.

Using Gradle, run
```bash
$ ./gradlew test
```

Using the REPL, start an nREPL server with `./gradlew clojureRepl` in one terminal, then use whatever means you have to
connect to an nREPL server (e.g., IntelliJ has a builtin connect to nREPL run configuration that can be added using
"Edit Configurations"). From the REPL run

```
=> require '[retroact.test] :reload)
```

You may rerun the above command as much as you like without restarting the nREPL server. You will, of course, need to
reload any namespaces that have changed if you're editing/writing code (:reload-all may be used instead of :reload to
facilitate this).


# Known Issues

## Drag and Drop

Retroact has its own thread for running diffs on views and calculating just what needs updating and doing. When actually
updating the onscreen components it uses the toolkit thread (EDT, in the case of Swing) to call the relevant methods
and constructors. Furthermore, the app state may be updated on whatever thread the app considers appropriate. This
mostly works well, but it doesn't work for all things. In the case of drag and drop there is a race condition. The app
state may update between the time the user starts the drag and the time data is retrieved from the app state. This is
decidedly different from the problem of the user _about_ to click on something and having it move or disappear. In this
case the user has already clicked, appears to have grabbed the thing and starts dragging it, but in fact, another thing
has been grabbed or nothing has been grabbed. This may be ok if the thing being dragged is displayed in a
distinguishable form (like a file name or image icon) so the user knows they did not get the thing they intended to get.

Perhaps applications need to lock the drag source view when something is running that may update the view. Rather,
disable dragging from that view and indicate it is not available for dragging.

Still, there are three threads involved here: the toolkit thread, Retroact thread, and whatever thread the application
has designated for app state changes. This may cause problems for other functionality besides drag and drop.
