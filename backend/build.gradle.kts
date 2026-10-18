plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
}
dependencies {
    implementation(project(":support-contracts"))
    implementation(project(":workspace-contracts"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("io.modelcontextprotocol.sdk:mcp:0.18.4")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
kotlin { jvmToolchain(21); compilerOptions { javaParameters.set(true) } }
tasks.test { useJUnitPlatform() }
tasks.bootJar { archiveFileName.set("backend.jar") }
