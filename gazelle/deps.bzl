"""Dependencies for the Clojure Gazelle plugin."""

def clojure_gazelle_dependencies():
    """Declares dependencies needed by the Clojure Gazelle extension.

    Call this in your WORKSPACE after loading rules_clojure.
    Note: rules_go and bazel_gazelle must already be set up.
    """
    pass  # All deps are pulled in transitively via rules_clojure
