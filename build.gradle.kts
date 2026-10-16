plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.spring") version "2.2.21" apply false
    id("org.springframework.boot") version "4.1.1" apply false
}
allprojects {
    group = "dev.aichallenge.assistants"
    version = "0.1.0"
    repositories { mavenCentral() }
}
