(ns tests.transitive.greeter.hello
    (:import (tests.transitive.greeter HelloJava)))

(defn greet [subject]
      (.greet (HelloJava.) subject))
