(ns rules-clojure.deterministic-fixture
  "Exercises the compiler paths that consume RT.nextID() or iterate
  ObjExpr.closes, for rules-clojure.persistent-classloader-test")

(defmacro twice [x]
  `(let [v# ~x]
     (+ v# v#)))

(defn closures [a b c d e f g h i j k l]
  (let [m (* a b)
        n (* c d)]
    (fn [x]
      [a b c d e f g h i j k l m n x (twice x)])))

(defn mutual [xs]
  (letfn [(ev? [n] (if (zero? n) true (od? (dec n))))
          (od? [n] (if (zero? n) false (ev? (dec n))))]
    (loop [xs xs
           acc []]
      (if (seq xs)
        (recur (rest xs) (conj acc #(+ % (first xs)) (ev? (first xs))))
        (reify Object
          (toString [_] (str (count acc))))))))

(def anon (map #(* %1 %2) [1 2] [3 4]))
