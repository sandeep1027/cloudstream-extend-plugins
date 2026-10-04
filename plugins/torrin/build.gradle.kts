import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    implementation(libs.kotlinx.serialization.json)
    // kotlinx-coroutines ships inside the app at runtime; compileOnly keeps
    // the plugin jar slim while allowing suspend/async usage.
    compileOnly(libs.kotlinx.coroutines.core)
}
