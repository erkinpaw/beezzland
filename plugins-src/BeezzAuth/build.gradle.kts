plugins {
    id("java-library")
}

group = "land.beezz"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:4.2.0")
    annotationProcessor("com.velocitypowered:velocity-api:4.2.0")
}

java {
    // our Velocity runs on Java 25 (same JVM as the Minecraft server)
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}
