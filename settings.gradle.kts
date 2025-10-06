pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "Zest"

// Include tool server module (Spring Boot with OpenAPI)
include("tool-server")