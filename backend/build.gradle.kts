// Versions are pinned and bumped by hand for now — no dependency-update bot
// is wired up.

plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.yzlaboratory"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // --- Web + actuator + validation -----------------------------------------
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // --- Security + Spring Session JDBC --------------------------------------
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.session:spring-session-jdbc")
    // Argon2id (spring-security-crypto) needs a Bouncy Castle provider.
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")

    // --- Persistence: SQLite + Flyway ----------------------------------------
    // Boot 4 modularised the Flyway autoconfiguration out of
    // spring-boot-autoconfigure into spring-boot-flyway — depending on
    // flyway-core alone would compile but never run migrations, so we pull the
    // Boot module (it brings flyway-core).
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("org.xerial:sqlite-jdbc:3.53.2.0")

    // --- Structured JSON logs for Grafana Cloud Loki -------------------------
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")

    // --- Tests ---------------------------------------------------------------
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // Boot 4 split the web test slices out of spring-boot-test-autoconfigure;
    // @AutoConfigureMockMvc and the MockMvc support now live in this module.
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Surface real parameter names for Spring's reflective binding (e.g.
    // @RequestParam, validation messages) without per-annotation boilerplate.
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = "0.8.13"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true  // Useful locally; not consumed by CI.
    }
}

// SonarQube's Java analyzer resolves types against the dependency jars, but
// `check` never assembles them — this stages the runtime classpath into a
// stable directory the scanner points at. CI runs
// `./gradlew check collectSonarLibraries`.
tasks.register<Copy>("collectSonarLibraries") {
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("sonar-libraries"))
}
