pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        // dl.google.com 404s the whole maven tree from this network, so AGP
        // resolves through the Aliyun Google mirror first; google() stays as
        // a fallback for networks where the origin works.
        maven("https://maven.aliyun.com/repository/google")
        google()
    }
}

rootProject.name = "kotlin-night-funkin"
