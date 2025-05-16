plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.zps"
version = "1.9.814-SNAPSHOT"

repositories {
    mavenCentral()
}


// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
//    version.set("243.25659.59")
    version.set("2023.1")

    type.set("IC") // Target IDE Platform

    plugins.set(listOf("java"/* Plugin Dependencies */))
}

dependencies {
    // JUnit Jupiter (JUnit 5)
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")

    // Mockito
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0") // For JUnit 5 integration

    // Mockito Kotlin (makes Mockito more Kotlin-friendly)
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")

    // MCP SDK dependencies
    implementation(platform("io.modelcontextprotocol.sdk:mcp-bom:0.9.0"))
    implementation("io.modelcontextprotocol.sdk:mcp")
    implementation("io.modelcontextprotocol.sdk:mcp-spring-webflux")
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }
    
    // Configure the test task to use JUnit Platform
    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    patchPluginXml {
        sinceBuild.set("223.0")
        untilBuild.set("243.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
