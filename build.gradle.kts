plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.zps"
version = "1.9.891"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3.4.1")
        
        // Plugin Dependencies
        bundledPlugin("com.intellij.java")
        
        // Required for testing
        pluginVerifier()
        zipSigner()
        instrumentationTools()
    }

    // JUnit 4 for IntelliJ platform tests
    testImplementation("junit:junit:4.13.2")

    // Mockito
    testImplementation("org.mockito:mockito-core:5.11.0")

    // Mockito Kotlin (makes Mockito more Kotlin-friendly)
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    
    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
    
    // ONNX Runtime - REQUIRED for ONNX models to work
//    implementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")

    // Optional but recommended for better performance
//    implementation("ai.djl:api:0.28.0")
//    implementation("ai.djl.onnxruntime:onnxruntime-engine:0.28.0")

    // If you still have issues, try adding:
//    implementation("com.microsoft.onnxruntime:onnxruntime_gpu:1.19.2")
    // LSP4J for language server protocol support
    
    // MCP SDK dependencies
    implementation(platform("io.modelcontextprotocol.sdk:mcp-bom:0.9.0"))
    implementation("io.modelcontextprotocol.sdk:mcp")
    implementation("io.modelcontextprotocol.sdk:mcp-spring-webflux")

    // Javalin for REST API server
    implementation("io.javalin:javalin:5.6.3")

    implementation("dev.langchain4j:langchain4j:0.35.0")
//
    // Tree-sitter for AST-based code chunking (correct Maven coordinates)
    implementation("io.github.bonede:tree-sitter:0.25.3")
    implementation("io.github.bonede:tree-sitter-java:0.23.4") 
    implementation("io.github.bonede:tree-sitter-kotlin:0.3.8.1")
    implementation("io.github.bonede:tree-sitter-javascript:0.23.1")
// https://mvnrepository.com/artifact/io.github.bonede/tree-sitter-typescript
    implementation("io.github.bonede:tree-sitter-typescript:0.23.2")
//        implementation("io.github.bonede:tree-sitter-python:0.24.4")

    // No Lucene dependencies needed - using in-memory index instead
    
    // Java Diff Utils for gdiff functionality
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")

}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    
    pluginConfiguration {
        id = "com.zps.zest"
        name = "Zest"
        vendor {
            name = "ZPS"
        }
    }
    
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    // Clean task to also remove the sandbox
    clean {
        delete("$projectDir/build/idea-sandbox")
    }

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
        sinceBuild.set("243.0")
        untilBuild.set("251.*") // Makes it compatible with 2024.3.x and beyond
    }
}
