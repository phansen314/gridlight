plugins {
    kotlin("jvm") version "2.4.21" apply false
    id("me.champeau.jmh") version "0.7.4" apply false
}

subprojects {
    repositories { mavenCentral() }
}
