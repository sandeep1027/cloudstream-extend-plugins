// Standalone build for the CloudStream plugins in this repository.
//
// The plugins are compiled against the CloudStream library module, which lives
// in the cloudstream-extend fork and is pulled in as the `cloudstream` submodule
// (see .gitmodules). Only that one module is built here — the app itself is not
// part of this repository.

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

plugins {
    // Auto-provision JDK 17 for toolchain
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        maven("https://jitpack.io")
    }
}

rootProject.name = "CloudStreamExtendPlugins"

// The library module, straight out of the submodule.
include(":library")
project(":library").projectDir = file("cloudstream/library")

include(
    ":plugins:anime",
    ":plugins:torrin",
    ":plugins:torrin-mdblist",
    ":plugins:torrin-trakt",
    ":plugins:hianime",
    ":plugins:anikoto",
    ":plugins:animecube",
    ":plugins:hdhub4u",
    ":plugins:bollyflix",
    ":plugins:prowlarr",
    ":plugins:yts"
)