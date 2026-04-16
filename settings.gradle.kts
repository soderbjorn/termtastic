rootProject.name = "Termtastic"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // JediTerm (headless VT emulator used server-side for AI assistant
        // state detection) is published to JetBrains' IntelliJ dependencies
        // repository, not Maven Central.
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
            mavenContent {
                includeGroupAndSubgroups("org.jetbrains.jediterm")
            }
        }
    }
}

include(":web")
include(":server")
include(":clientServer")
include(":client")
include(":electron")
include(":terminal-emulator")
include(":terminal-view")
include(":androidApp")