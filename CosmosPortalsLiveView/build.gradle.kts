plugins {
    id("net.minecraftforge.gradle") version "[6.0.36,6.2)"
    java
}

version = "1.20.1-1.0.0"
group = "com.blackwell.cosmosportalsliveview"

base {
    archivesName.set("CosmosPortalsLiveView")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    maven { url = uri("https://maven.minecraftforge.net/") }
}

minecraft {
    mappings("official", "1.20.1")
}

dependencies {
    minecraft("net.minecraftforge:forge:1.20.1-47.4.20")
    compileOnly(files("libs/cosmos-library-1.20.1-10.6.0.0-universal.jar"))
    compileOnly(files("libs/cosmosportals-1.20.1-7.5.0.0-universal.jar"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
