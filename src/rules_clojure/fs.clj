(ns rules-clojure.fs
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str])
  (:import java.io.File
           [java.nio.file CopyOption Files Path Paths SimpleFileVisitor StandardCopyOption FileVisitOption FileVisitResult FileAlreadyExistsException LinkOption]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]))

(set! *warn-on-reflection* true)

(defn path? [x]
  (instance? Path x))

(s/def ::path path?)

(defn file? [x]
  (instance? File x))

(s/fdef absolute? :args (s/cat :p path?) :ret boolean?)
(defn absolute? [^Path path]
  (.isAbsolute path))

(s/def ::absolute-path (s/and path? absolute?))

(s/fdef ->path :args (s/cat :dirs (s/* (s/alt :s string? :p path?))) :ret path?)
(defn ->path [& dirs]
  (let [[d & dr] dirs
        d (if (string? d)
            (Paths/get d (into-array String []))
            d)]
    (assert d (print-str "path does not exist:" d))
    (reduce (fn [^Path p ^String dir]
              (.resolve p dir)) d
            (rest (map str dirs)))))

(defn file->path [^File f]
  (.toPath f))

(defn path->file ^File [^Path p]
  (.toFile p))

(s/fdef absolute :args (s/cat :p path?) :ret path?)
(defn absolute ^Path [^Path path]
  (.toAbsolutePath path))

(s/fdef relative-to :args (s/cat :a path? :b path?) :ret path?)
(defn path-relative-to
  "Return the path to b, relative to a"
  [^Path a ^Path b]
  {:pre []}
  (.relativize (absolute a) (absolute b)))

(s/fdef normal-file? :args (s/cat :f file?) :ret boolean?)
(defn normal-file? [^File file]
  (.isFile file))

(s/fdef directory? :args (s/cat :f file?) :ret boolean?)
(defn directory? [^File file]
  (.isDirectory file))

(defn create-directories [path]
  (Files/createDirectories path (into-array java.nio.file.attribute.FileAttribute [])))

(defn exists? [path]
  (Files/exists path (into-array java.nio.file.LinkOption [])))

(defn ensure-directory [path]
  (create-directories path))

(s/fdef filename :args (s/cat :p path?) :ret string?)
(defn filename [^Path path]
  (-> path
      .getFileName
      str))

(s/fdef dirname :args (s/cat :p path?) :ret path?)
(defn dirname [^Path path]
  (.getParent path))

(s/fdef extension :args (s/cat :p path?) :ret string?)
(defn extension [path]
  (-> path
      filename
      (str/split #"\.")
      rest
      last))

(defn basename [path]
  (-> path
      filename
      (str/split #"\.")
      first))

(s/fdef ls :args (s/cat :d path?) :ret (s/coll-of path?))
(defn ls [^Path dir]
  (-> dir
      .toFile
      .listFiles
      (->>
       (map (fn [^File f]
              (.toPath f))))))

(defn ls-r [dir]
  (->> dir
       ls
       (mapcat (fn [^Path path]
                 (if (-> path .toFile directory?)
                   (concat [path] (ls-r path))
                   [path])))))

(s/fdef jar? :args (s/cat :f path?) :ret boolean?)
(defn jar? [path]
  (= "jar" (extension path)))

(s/fdef mv :args (s/cat :s path? :d path?))
(defn mv [src dest]
  (Files/move src dest (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE])))

(defn cp [^Path src ^Path dest]
  (Files/copy src dest ^CopyOption/1 (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING
                                               StandardCopyOption/COPY_ATTRIBUTES ])))

(defn rm-rf [^Path dir]
  (while (seq (ls dir))
    (doseq [^Path p (ls dir)
            :let [f (path->file p)]]
      (if (directory? f)
        (do
          (rm-rf p)
          (.delete f))
        (.delete f))))
  (-> dir path->file .delete))

(defn cp-r [^Path src ^Path dest]
  {:pre [(path? src)
         (path? dest)
         (directory? (path->file src))
         (directory? (path->file dest))]}
  (Files/walkFileTree src
                        #{FileVisitOption/FOLLOW_LINKS}
                        Integer/MAX_VALUE
                        (proxy [SimpleFileVisitor] []
                          (preVisitDirectory [^Path dir attrs]
                            (let [target ^Path (.resolve dest (.relativize src dir))]
                              (try
                                (Files/createDirectories target (into-array java.nio.file.attribute.FileAttribute []))
                                (catch FileAlreadyExistsException e
                                  (when-not (Files/isDirectory target (into-array LinkOption []))
                                    (throw e))))
                              FileVisitResult/CONTINUE))

                          (visitFile [^Path f attrs]
                            (Files/copy f (.resolve dest (.relativize src f)) ^CopyOption/1 (into-array CopyOption []))
                            FileVisitResult/CONTINUE))))

(defn clean-directory [path]
  (rm-rf path)
  (create-directories path))

(defn new-temp-dir
  ([prefix]
   (Files/createTempDirectory prefix (into-array FileAttribute [])))
  ([dir prefix]
   (Files/createTempDirectory dir prefix (into-array FileAttribute []))))

(defn new-temp-file [dir prefix suffix]
  (Files/createTempFile (->path dir) prefix suffix (into-array FileAttribute [])))

(defn shasum [^Path path]
  {:pre [(path? path)]
   :post [(string? %)]}
  (let [digest (MessageDigest/getInstance "SHA-256")
        encoder (java.util.Base64/getEncoder)]

    (->> path
        (Files/readAllBytes)
        (.digest digest)
        (.encodeToString encoder))))

(defn bazel-hash
  "Given the bytes from a file, return the hash"
  [bs]
  (let [digest (MessageDigest/getInstance "SHA-256")
        b64encoder (java.util.Base64/getEncoder)
        hexer (java.util.HexFormat/of)]
    ;; yes, this is double encoded. I don't know why
    (->> bs
         (.digest digest)
         (.formatHex hexer)
         (#(String/.getBytes % "UTF-8"))
         (.encodeToString b64encoder))))

(defn bazelsum
  "given a path, hash the file contents the same way bazel does. This
will match the contents of :input-map"
  [^Path path]

  (->> path
       (Files/readAllBytes)
       (bazel-hash)))
