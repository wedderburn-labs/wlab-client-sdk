import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.vanniktechMavenPublish)
}

group = "za.co.wlab"
version = System.getenv("RELEASE_VERSION") ?: "0.0.1-SNAPSHOT"

sqldelight {
    databases {
        create("Database") {
            packageName.set("org.traccar.client.db")
        }
    }
}

kotlin {
    coreLibrariesVersion = libs.versions.consumer.stdlib.get()
    compilerOptions {
        val target = KotlinVersion.fromVersion(libs.versions.consumer.kotlin.get())
        languageVersion.set(target)
        apiVersion.set(target)
    }

    android {
        namespace = "org.traccar.client"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources.enable = true
        withHostTest {}
    }

    val xcframework = XCFramework("TraccarClientSDK")

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "TraccarClientSDK"
            isStatic = true
            xcframework.add(this)
        }
        iosTarget.binaries.configureEach {
            if (this is TestExecutable) linkerOpts("-lsqlite3")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.android)
            implementation(libs.androidx.activity)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.play.services.location)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    // Fork releases ship inside the npm tarball, not through Maven Central.
    if (System.getenv("UNSIGNED_RELEASE") == null) signAllPublications()
    coordinates("za.co.wlab", "wlab-client-sdk", version.toString())
    pom {
        name.set("WLAB Client SDK")
        description.set("Wedderburn Labs fork of the Traccar Client SDK: Kotlin Multiplatform location tracking.")
        url.set("https://github.com/wedderburn-labs/wlab-client-sdk")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("tananaev")
                name.set("Anton Tananaev")
                email.set("anton@traccar.org")
            }
        }
        scm {
            url.set("https://github.com/wedderburn-labs/wlab-client-sdk")
            connection.set("scm:git:https://github.com/wedderburn-labs/wlab-client-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/wedderburn-labs/wlab-client-sdk.git")
        }
    }
}
