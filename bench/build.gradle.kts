plugins {
    kotlin("jvm") version "2.4.21"
    id("me.champeau.jmh") version "0.7.4"
}

repositories { mavenCentral() }

kotlin { jvmToolchain(25) }

dependencies {
    implementation("org.openjdk.jol:jol-core:0.17") // memory-per-board report only
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); showStandardStreams = false }
}

jmh {
    jmhVersion.set("1.37")
    // Defaults are JMH's own (5 forks x 5 iterations x 10 s). For a quick run pass e.g.
    //   ./gradlew jmh -Pjmh.smoke
    // or build the jar and drive JMH directly (see REPORT.md).
    if (project.hasProperty("jmh.smoke")) {
        fork.set(1)
        warmupIterations.set(2)
        iterations.set(3)
        warmup.set("300ms")
        timeOnIteration.set("300ms")
    }
    (project.findProperty("jmh.includes") as String?)?.let { includes.set(listOf(it)) }
    resultFormat.set("JSON")
}

// Prints JOL-measured retained size per board.
tasks.register<JavaExec>("memoryReport") {
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("gridlight.bench.MemoryReportKt")
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading", "-Djol.magicFieldOffset=true")
}
