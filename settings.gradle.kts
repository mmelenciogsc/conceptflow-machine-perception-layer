// SPDX-License-Identifier: MIT OR Apache-2.0
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "conceptflow-machine-perception-layer"

include(":packages:android-protocol")
include(":packages:android-live-transport")
include(":apps:rokid-client")
include(":apps:android-host")
