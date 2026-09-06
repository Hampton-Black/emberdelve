plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.javalin:javalin:6.7.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.4")
    // RollRequest carries Optional fields (§6); Jackson needs this to serialise them.
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.18.4")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.4")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "dm.App"
}

// Jackson binds JSON straight into record components by parameter name.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

// Regenerates src/test/resources/sessions/crypt-fight.jsonl after a schema bump.
// Old logs are refused rather than migrated, so the fixture must be reproducible.
tasks.register<JavaExec>("recordFixture") {
    group = "verification"
    description = "Re-records the replay fixture at the current Event.SCHEMA_VERSION"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dm.replay.FixtureRecorder")
}
