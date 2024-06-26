(ns rules-clojure.fs
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import java.io.File
           [java.nio.file CopyOption Files FileSystem FileSystems Path Paths StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

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
    (assert d (str "path does not exist:" d))
    (reduce (fn [^Path p dir] (.resolve p dir)) d (rest dirs))))

(defn file->path [^File f]
  (.toPath f))

(defn path->file [^Path p]
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
(defn filename [path]
  (-> path
      .getFileName
      str))

(s/fdef dirname :args (s/cat :p path?) :ret path?)
(defn dirname [path]
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

(s/fdef ls-r :args (s/cat :d path?) :ret (s/coll-of path?))
(defn ls-r
  "recursive list"
  [dir]
  (->> dir
       ls
       (mapcat (fn [path]
                 (if (-> path .toFile directory?)
                   (concat [path] (ls-r path))
                   [path])))))

(s/fdef jar? :args (s/cat :f path?) :ret boolean?)
(defn jar? [path]
  (= "jar" (extension path)))


(s/fdef mv :args (s/cat :s path? :d path?))
(defn mv [src dest]
  (Files/move src dest (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE])))

(defn rm-rf [^Path dir]
  (while (seq (ls dir))
    (doseq [p (ls dir)
            :let [f (path->file p)]]
      (if (directory? f)
        (do
          (rm-rf p)
          (.delete f))
        (.delete f))))
  (-> dir path->file .delete))

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

(defn spit-file [f content & opts]
  (apply spit f (str content "\n") opts))
