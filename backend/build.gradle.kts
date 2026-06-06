// Spring Boot 4 backend per ADR 0014 (stack) and ADR 0021 (auth in-app).
// Versions intentionally pinned — Renovate will surface updates as PRs once
// it's enabled. SonarQube Cloud (ADR 0022) consumes the JaCoCo XML this build
// emits at backend/build/reports/jacoco/test/jacocoTestReport.xml.

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
        // Java 25 LTS, per ADR 0014. Gradle's toolchain machinery auto-provisions
        // a matching JDK if the developer's machine doesn't have one.
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

    // --- Security + Spring Session JDBC (ADR 0021) ---------------------------
    // Session store is JDBC-backed so a container restart preserves logins
    // (ADR 0021, "Sessions"). Argon2id is provided by spring-security-crypto
    // with a Bouncy Castle backend.
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.session:spring-session-jdbc")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")

    // --- Persistence: SQLite + Flyway ----------------------------------------
    // SQLite is the single datastore (ADR 0014). Flyway runs at boot to apply
    // schema migrations from src/main/resources/db/migration.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.xerial:sqlite-jdbc:3.53.2.0")

    // --- Structured JSON logs for Grafana Cloud Loki (ADR 0014) --------------
    // logback-spring.xml picks this up under the `prod` profile and emits JSON
    // to stdout; the Alloy companion on the EC2 host scrapes the Docker log
    // socket and ships to Loki.
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")

    // --- Tests ---------------------------------------------------------------
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
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
        xml.required = true   // SonarQube Cloud reads this (ADR 0022).
        html.required = true  // Useful locally; not consumed by CI.
    }
}

// `check` is the Java plugin's standard aggregate (test + verification).
// `test` is `finalizedBy(jacocoTestReport)` above, so `./gradlew check` runs
// the full local gate including coverage — the JVM-side counterpart to the
// frontend's `npm run check`.
