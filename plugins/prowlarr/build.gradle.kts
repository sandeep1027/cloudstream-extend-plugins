import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get().toInt())
    targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    // Compiled against the app's own library module so the plugin always
    // matches the exact MainAPI surface shipped in this build.
    compileOnly(project(":library"))
    implementation(libs.nicehttp)
    // Prowlarr's response is read with org.json rather than a generated
    // kotlinx.serialization serializer: the serializer would be compiled against this
    // build's kotlinx.serialization but resolve against the app's at runtime, and the
    // two generations disagree on GeneratedSerializer (AbstractMethodError on device).
    // org.json is part of the Android framework, so there is nothing to keep in step.
    implementation(libs.json)
    // kotlinx-coroutines ships inside the app at runtime; compileOnly keeps
    // the plugin jar slim while allowing suspend/async usage.
    compileOnly(libs.kotlinx.coroutines.core)
}
