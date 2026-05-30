(ns rules-clojure.testrunner-test
  (:require [clojure.test :as t :refer [deftest is]]
            [clojure.xml :as xml]
            [rules-clojure.testrunner :as tr])
  (:import [java.io ByteArrayInputStream Closeable File PrintWriter StringWriter]))

(defn parse
  "Parse an XML string into clojure.xml's element tree. Doubles as a
   well-formedness check — malformed XML throws."
  [^String s]
  (xml/parse (ByteArrayInputStream. (.getBytes s "UTF-8"))))

(defn elements
  "Child element nodes of `node`, skipping the whitespace text nodes that the
   SAX parser emits between our pretty-printed elements."
  [node]
  (filter map? (:content node)))

(defn by-name
  "Index testcase element nodes by their name attribute. Test execution order
   isn't guaranteed, so we look cases up by name rather than position."
  [cases]
  (into {} (map (juxt #(get-in % [:attrs :name]) identity)) cases))

;; Serializer golden tests: hand-built inputs cover shaping a single-namespace
;; live run can't reach (multiple suites, escaping). The expected XML is embedded
;; adjacent to each test so a format change surfaces as a readable diff here.

(def sample
  {:cases [{:name "passing-test" :classname "my.ns" :time 0.01
            :failures [] :errors []}
           {:name "failing-test" :classname "my.ns" :time 0.02
            :failures [{:message "my.ns/failing-test (core.clj:5)"
                        :body "expected: 1  actual: 2"}]
            :errors []}
           {:name "erroring-test" :classname "other.ns" :time 0.0
            :failures []
            :errors [{:message "other.ns/erroring-test (core.clj:9)"
                      :body "boom <&> \"quote\""}]}]})

(def sample-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<testsuites>
  <testsuite name=\"my.ns\" tests=\"2\" failures=\"1\" errors=\"0\" time=\"0.030\">
    <testcase name=\"passing-test\" classname=\"my.ns\" time=\"0.010\"/>
    <testcase name=\"failing-test\" classname=\"my.ns\" time=\"0.020\">
    <failure message=\"my.ns/failing-test (core.clj:5)\">expected: 1  actual: 2</failure>
    </testcase>
  </testsuite>
  <testsuite name=\"other.ns\" tests=\"1\" failures=\"0\" errors=\"1\" time=\"0.000\">
    <testcase name=\"erroring-test\" classname=\"other.ns\" time=\"0.000\">
    <error message=\"other.ns/erroring-test (core.clj:9)\">boom &lt;&amp;&gt; &quot;quote&quot;</error>
    </testcase>
  </testsuite>
</testsuites>
")

(deftest sample-serializes-to-golden
  (is (= sample-xml (tr/results->junit-xml sample))))

(def empty-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<testsuites>
</testsuites>
")

(deftest empty-results-serialize-to-golden
  (is (= empty-xml (tr/results->junit-xml {:cases []}))))

(def multiline-results
  {:cases [{:name "t" :classname "my.ns" :time 0.0
            :failures [{:message "line1\nline2" :body "b"}]
            :errors []}]})

(def multiline-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<testsuites>
  <testsuite name=\"my.ns\" tests=\"1\" failures=\"1\" errors=\"0\" time=\"0.000\">
    <testcase name=\"t\" classname=\"my.ns\" time=\"0.000\">
    <failure message=\"line1&#10;line2\">b</failure>
    </testcase>
  </testsuite>
</testsuites>
")

(deftest multiline-message-serializes-to-golden
  ;; A newline in a failure message must be encoded as a numeric reference so it
  ;; survives as an attribute value rather than being normalized to a space.
  (is (= multiline-xml (tr/results->junit-xml multiline-results))))

(deftest end-var-without-begin-leaves-zero-time
  (binding [tr/*results* (atom {:cases []})]
    (tr/pretty-report {:type :end-test-var})
    (let [c (first (:cases @tr/*results*))]
      (is (= 0 (:time c)) "no start recorded -> time stays at its 0 default")
      (is (not (contains? c :start)) ":start must not leak into the recorded case"))))

(defn run-fixture
  "Run the-ns through the real runner, writing XML to a temp file, and return
   {:summary <clojure.test summary> :doc <parsed XML root>}. Silences the
   runner's console + test output."
  [the-ns]
  (let [out (File/createTempFile "junit" ".xml")]
    ;; The JDK ships no Closeable that deletes a file, so reify one and let
    ;; with-open delete the temp file on scope exit.
    (with-open [_ (reify Closeable (close [_] (.delete out)))]
      (let [sink (PrintWriter. (StringWriter.))
            summary (binding [*out* sink, t/*test-out* sink]
                      (tr/run-ns the-ns (.getAbsolutePath out)))]
        {:summary summary :doc (parse (slurp out))}))))

(deftest passing-namespace-runs-clean
  (let [{:keys [summary doc]} (run-fixture 'rules-clojure.testrunner-fixtures.passing)
        suites (elements doc)
        cases (elements (first suites))]
    (is (zero? (:fail summary)))
    (is (zero? (:error summary)))
    (is (= 1 (count suites)) "one suite for the one namespace run")
    (is (= "rules-clojure.testrunner-fixtures.passing"
           (get-in (first suites) [:attrs :name])))
    (is (= 2 (count cases)))
    (is (every? #(empty? (elements %)) cases) "passing tests have no children")
    (is (every? #(<= 0 (Double/parseDouble (get-in % [:attrs :time]))) cases)
        "each testcase carries a non-negative time")))

(deftest mixed-namespace-classifies-and-counts
  (let [{:keys [summary doc]} (run-fixture 'rules-clojure.testrunner-fixtures.mixed)
        suite (first (elements doc))
        cases (by-name (elements suite))]
    (is (= 1 (:fail summary)))
    (is (= 1 (:error summary)))
    (is (= "3" (get-in suite [:attrs :tests])))
    (is (= "1" (get-in suite [:attrs :failures])))
    (is (= "1" (get-in suite [:attrs :errors])))
    (is (empty? (elements (cases "a-pass"))) "passing test is self-closing")
    (is (= [:failure] (map :tag (elements (cases "a-fail")))) "assertion failure -> <failure>")
    (is (= [:error] (map :tag (elements (cases "an-error")))) "thrown exception -> <error>")))

(deftest fixture-throw-becomes-errored-case
  (let [{:keys [summary doc]} (run-fixture 'rules-clojure.testrunner-fixtures.bad-fixture)
        cases (by-name (mapcat elements (elements doc)))]
    (is (= 1 (:error summary)) "a throwing :once fixture is one error")
    (is (contains? cases "fixture-error"))
    (is (= [:error] (map :tag (elements (cases "fixture-error")))))))

(deftest missing-namespace-becomes-load-error
  (let [{:keys [summary doc]} (run-fixture 'rules-clojure.testrunner-fixtures.does-not-exist)
        cases (by-name (mapcat elements (elements doc)))]
    (is (= 1 (:error summary)) "a namespace that won't load is one error")
    (is (contains? cases "load"))
    (is (= [:error] (map :tag (elements (cases "load")))))))
