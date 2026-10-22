plugins {
    id("java-library")
}

group = "land.beezz"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    compileOnly("net.luckperms:api:5.4")
}

java {
    // Minecraft 26.3 servers run on Java 25
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.processResources {
    val props = mapOf("version" to version)
    filesMatching("plugin.yml") { expand(props) }
}
