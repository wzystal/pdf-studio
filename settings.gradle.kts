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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "PdfStudio"

include(":app")
include(":core:common")
include(":core:pdf-engine")
include(":core:pdf-render")
include(":core:pdf-annot")
include(":core:storage")
include(":feature:filelist")
include(":feature:reader")
include(":feature:editor")
include(":feature:pageops")
