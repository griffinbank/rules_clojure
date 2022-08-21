(ns rules-clojure.persistentClassLoader
  (:gen-class
   :init init
   :constructors {["[Ljava.net.URL;" "java.lang.ClassLoader"] ["[Ljava.net.URL;" "java.lang.ClassLoader"]
                  ["[Ljava.net.URL;"] ["[Ljava.net.URL;"]}
   :extends java.net.URLClassLoader
   :exposes-methods {;; findClass parentFindClass
                     ;; loadClass parentLoadClass
                     addURL parentAddURL}))

(defn -init
  ([urls]
   [[urls] nil])
  ([urls parent]
   [[urls parent] nil]))

(defn -addURL [this url]
  (.parentAddURL this url))
