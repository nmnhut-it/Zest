import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.time.Duration

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.zps"
version = "1.9.911"

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
        bundledPlugin("org.jetbrains.kotlin")

        // Required for testing
        pluginVerifier()
        zipSigner()
        instrumentationTools()

        // IntelliJ Platform testing framework
        testFramework(TestFrameworkType.Platform)
        // Add Java test framework for LightJavaCodeInsightFixtureTestCase
        testFramework(TestFrameworkType.Plugin.Java)
    }

    // Model Context Protocol (MCP) SDK (convenience bundle with core + Jackson)
    implementation("io.modelcontextprotocol.sdk:mcp:0.15.0")

    // Jetty for embedded HTTP server (Jakarta EE 10 compatible)
    implementation("org.eclipse.jetty:jetty-server:12.0.15")
    implementation("org.eclipse.jetty.ee10:jetty-ee10-servlet:12.0.15")

    // JUnit 4 for IntelliJ platform tests
    testImplementation("junit:junit:4.13.2")

    // Mockito
    testImplementation("org.mockito:mockito-core:5.11.0")

    // Mockito Kotlin (makes Mockito more Kotlin-friendly)
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")

    // MockWebServer for integration testing with mock HTTP server
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // Kotlin Coroutines - use compileOnly to avoid conflicts with IntelliJ Platform's bundled version
    // The IntelliJ Platform 2024.3 bundles kotlinx-coroutines 1.8+
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")

    // For tests, also use compileOnly to use the platform's version
    testCompileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // https://mvnrepository.com/artifact/dev.langchain4j/langchain4j
    implementation("dev.langchain4j:langchain4j:1.7.1")

    // https://mvnrepository.com/artifact/dev.langchain4j/langchain4j-agentic
    implementation("dev.langchain4j:langchain4j-agentic:1.7.1-beta14")

    // https://mvnrepository.com/artifact/dev.langchain4j/langchain4j-open-ai
    implementation("dev.langchain4j:langchain4j-open-ai:1.7.1")

    // https://mvnrepository.com/artifact/dev.langchain4j/langchain4j-http-client-jdk
    implementation("dev.langchain4j:langchain4j-http-client-jdk:1.7.1")

    // Tree-sitter for AST-based code chunking
    implementation("io.github.bonede:tree-sitter:0.25.3")
    implementation("io.github.bonede:tree-sitter-java:0.23.4")
    implementation("io.github.bonede:tree-sitter-kotlin:0.3.8.1")
    implementation("io.github.bonede:tree-sitter-javascript:0.23.1")
    implementation("io.github.bonede:tree-sitter-typescript:0.23.2")

    // Java Diff Utils for gdiff functionality
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")

    // JGit for pure-Java Git operations (no process spawning, EDT-safe)
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")

    // Gson for JSON serialization (used by custom LangChain4j codec)
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Enhanced file pattern matching with proper glob support
    implementation("org.apache.commons:commons-io:1.3.2")
    
    // JSON processing for ripgrep output parsing
    implementation("com.google.code.gson:gson:2.10.1") // Already available
    
    // Markdown parsing and rendering for chat messages
    implementation("org.commonmark:commonmark:0.23.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.23.0")
    implementation("org.commonmark:commonmark-ext-heading-anchor:0.23.0")
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
        options.compilerArgs.add("-parameters")  // Preserve parameter names for reflection
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
        kotlinOptions.javaParameters = true  // Preserve parameter names for Kotlin
    }

    // Add this to ensure resources are properly packaged
    processResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE

        // Include ripgrep binaries in the JAR
        from("src/main/resources/bin") {
            into("bin")
            include("**/*")
        }
    }

    // Configure the test task for IntelliJ platform tests
    test {
        useJUnit()

        // IntelliJ Platform specific JVM arguments
        jvmArgs(
            "-ea", // Enable assertions
            "-Xmx3g", // More memory for IntelliJ platform tests
            "-XX:+UseG1GC",

            // IntelliJ Platform specific properties
            "-Djava.awt.headless=true", // Headless mode for CI
            "-Didea.platform.prefix=Idea",
            "-Didea.test.cyclic.buffer.size=1048576",

            // Disable GUI components for testing
            "-Djb.privacy.policy.text=<!--999.999-->",
            "-Djb.consents.confirmation.enabled=false",

            // Enable proper EDT handling
            "-Dswing.bufferPerWindow=false",
            "-Dsun.awt.noerasebackground=true",

            // Platform testing optimizations
            "-Didea.fatal.error.notification=disabled",
            "-Didea.ui.icons.svg.disk.cache=false",

            // Security properties for tests
            "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.font=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED",
            "--add-exports=java.base/sun.security.util=ALL-UNNAMED"
        )

        // Test environment properties
        systemProperties(
            mapOf(
                // Remove the invalid test execution policy
                "java.awt.headless" to "true",
                "idea.test" to "true"
            )
        )

        // Increased timeout for platform tests
        timeout.set(Duration.ofMinutes(15))

        // Test logging
        testLogging {
            events("started", "passed", "skipped", "failed", "standardOut", "standardError")
            showStandardStreams = false
            showExceptions = true
            showStackTraces = true

            afterSuite(KotlinClosure2({ desc: TestDescriptor, result: TestResult ->
                if (desc.parent == null) {
                    val testType = "IntelliJ Platform Tests"
                    val output =
                        "$testType: ${result.resultType} " + "(${result.testCount} tests, " + "${result.successfulTestCount} passed, " + "${result.failedTestCount} failed, " + "${result.skippedTestCount} skipped)"

                    val border = "=".repeat(output.length)
                    println("\n$border")
                    println(output)
                    println("$border\n")

                    if (result.failedTestCount > 0) {
                        println("❌ Some IntelliJ platform tests failed!")
                        println("Check test reports for detailed information")
                    } else {
                        println("✅ All IntelliJ platform tests passed!")
                    }
                }
            }))
        }

        // Keep at 1 for EDT thread safety
        maxParallelForks = 1
    }

    // IntelliJ-specific test tasks
    register<Test>("testCompletion") {
        description = "Run only completion service tests"
        group = "verification"

        useJUnit()
        include("**/completion/**")

        outputs.upToDateWhen { false } // Always run
    }

    register<Test>("testIntellij") {
        description = "Run IntelliJ platform integration tests"
        group = "intellij"

        useJUnit()
        include("**/*IntelliJ*")
        include("**/integration/**")

        // Extended timeout for integration tests
        timeout.set(Duration.ofMinutes(20))

        doFirst {
            println("Running IntelliJ Platform Integration Tests...")
        }
    }

    register<Test>("testPerformance") {
        description = "Run performance tests"
        group = "verification"

        useJUnit()
        include("**/*Performance*")

        // Performance tests need more resources
        maxHeapSize = "4g"
        jvmArgs("-XX:+UnlockExperimentalVMOptions", "-XX:+UseZGC")

        doFirst {
            println("Running Performance Tests...")
        }
    }

    // Combined completion test task
    register("checkCompletion") {
        group = "verification"
        description = "Run all completion service tests and generate reports"

        dependsOn("testCompletion", "testPerformance", "testIntellij")

        doLast {
            println("✅ All completion service tests completed!")
        }
    }

    patchPluginXml {
        sinceBuild.set("243.0")
        untilBuild.set("259.*") // Makes it compatible with 2024.3.x and beyond
    }
}
