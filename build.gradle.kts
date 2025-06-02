plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.zps"
version = "1.9.840-SNAPSHOT"

repositories {
    mavenCentral()
}


// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2024.3.4.1")
    type.set("IC") // Target IDE Platform
    // Add this to help with resource loading
    sandboxDir = "$projectDir/build/idea-sandbox"

    // Ensure all dependencies are included
    downloadSources.set(true)
    // This might help with classloader issues
    sameSinceUntilBuild.set(false)
    plugins.set(listOf("java"/* Plugin Dependencies */))
}

dependencies {
    // JUnit 4 for IntelliJ platform tests
    testImplementation("junit:junit:4.13.2")

    // Mockito
    testImplementation("org.mockito:mockito-core:5.11.0")

    // Mockito Kotlin (makes Mockito more Kotlin-friendly)
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    // ONNX Runtime - REQUIRED for ONNX models to work
    implementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")

    // Optional but recommended for better performance
    implementation("ai.djl:api:0.28.0")
    implementation("ai.djl.onnxruntime:onnxruntime-engine:0.28.0")

    // If you still have issues, try adding:
//    implementation("com.microsoft.onnxruntime:onnxruntime_gpu:1.19.2")
    // LSP4J for language server protocol support
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.20.1")
    
    // MCP SDK dependencies
    implementation(platform("io.modelcontextprotocol.sdk:mcp-bom:0.9.0"))
    implementation("io.modelcontextprotocol.sdk:mcp")
    implementation("io.modelcontextprotocol.sdk:mcp-spring-webflux")

    implementation("dev.langchain4j:langchain4j:0.35.0")
    implementation("dev.langchain4j:langchain4j-embeddings:0.35.0")

    // ONNX embedding models
    implementation("dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2:0.35.0")
    implementation("dev.langchain4j:langchain4j-embeddings-bge-small-en-v15-q:0.35.0")

    // Document processing
    implementation("dev.langchain4j:langchain4j-document-parser-apache-tika:0.35.0")

    // Apache Tika
    implementation("org.apache.tika:tika-core:2.9.2")
    implementation("org.apache.tika:tika-parsers-standard-package:2.9.2")
    
    // Apache Lucene for name-based search
    implementation("org.apache.lucene:lucene-core:9.11.1")
    implementation("org.apache.lucene:lucene-analyzers-common:8.11.3")
    implementation("org.apache.lucene:lucene-queryparser:9.11.1")
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
    // Add this to ensure resources are properly packaged
    processResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    // Configure the test task for JUnit 4 (IntelliJ platform tests)
    test {
        useJUnit()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    patchPluginXml {
        sinceBuild.set("223.0")
        untilBuild.set("251.*") // Makes it compatible with 2024.3.x
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
