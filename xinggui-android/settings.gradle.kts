pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "XingGui"

// P2: single-module start. To split later, create the dirs and uncomment:
// include(":core:design")   // design tokens / shared widgets
// include(":core:common")   // utils, Result, logging
// include(":core:network")  // OkHttp + aggregation API
// include(":core:data")     // repositories / Room / DataStore
// include(":core:player")   // Media3 playback core
// include(":feature:home")
// include(":feature:playlist")
// include(":feature:player")
// include(":feature:download")
// include(":feature:mine")
// include(":feature:auth")
// include(":feature:settings")
include(":app")