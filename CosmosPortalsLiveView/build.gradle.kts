plugins {
    id("net.minecraftforge.gradle") version "[6.0.36,6.2)"
    id("org.spongepowered.mixin") version "0.7.+"
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
    maven { url = uri("https://repo.spongepowered.org/repository/maven-public/") }
}

minecraft {
    mappings("official", "1.20.1")
}

mixin {
    add(sourceSets["main"], "cosmosportals_liveview.refmap.json")
    config("cosmosportals_liveview.mixins.json")
}

dependencies {
    minecraft("net.minecraftforge:forge:1.20.1-47.4.20")
    compileOnly(files("libs/cosmos-library-1.20.1-10.6.0.0-universal.jar"))
    compileOnly(files("libs/cosmosportals-1.20.1-7.5.0.0-universal.jar"))
    annotationProcessor("org.spongepowered:mixin:0.8.5:processor")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
