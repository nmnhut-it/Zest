plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.zps"
version = "1.9.865-SNAPSHOT"

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
    // Apache Tika
//    implementation("org.apache.tika:tika-core:2.9.2")
//    implementation("org.apache.tika:tika-parsers-standard-package:2.9.2")
    
    // No Lucene dependencies needed - using in-memory index instead
    
    // Java Diff Utils for gdiff functionality
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")
    
    // GumTree for AST-based diffing - using only core which is reliably available
//    implementation("com.github.gumtreediff:core:3.0.0")
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
