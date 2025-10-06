plugins {
    java
}

group = "com.zps.zest"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Use Spring Boot BOM for version management
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.4.1"))

    // Spring Boot dependencies (versions managed by BOM)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // OpenAPI/Swagger documentation
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.4")

    // LangChain4j for tool annotations
    implementation("dev.langchain4j:langchain4j:1.7.1")

    // JSON processing
    implementation("com.google.code.gson:gson:2.10.1")

    // Logging
    implementation("org.slf4j:slf4j-api")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
