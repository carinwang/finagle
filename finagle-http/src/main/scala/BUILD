scala_library(
    sources = rglobs("*.scala"),
    compiler_option_sets = {"fatal_warnings"},
    provides = scala_artifact(
        org = "com.twitter",
        name = "finagle-http",
        repo = artifactory,
    ),
    dependencies = [
        "3rdparty/jvm/com/google/code/findbugs:jsr305",
        "3rdparty/jvm/io/netty",
        "finagle/finagle-base-http",
        "finagle/finagle-core",
        "finagle/finagle-http2",
        "finagle/finagle-netty3",
        "finagle/finagle-netty4",
        "finagle/finagle-netty4-http",
        "finagle/finagle-toggle",
        scoped(
            "util/util-app",
            scope = "forced",
        ),
        "util/util-codec",
        "util/util-core",
        "util/util-logging",
        "util/util-stats",
    ],
    exports = [
        "3rdparty/jvm/io/netty",
        "finagle/finagle-base-http",
        "finagle/finagle-core",
        "util/util-core",
    ],
)
