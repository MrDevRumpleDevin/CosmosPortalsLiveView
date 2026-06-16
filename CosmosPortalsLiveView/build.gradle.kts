plugins {
    id("net.minecraftforge.gradle") version "[6.0.36,6.2)"
}

version = "1.20.1-1.0.0"
group = "com.blackwell.cosmosportalsliveview"

base {
    archivesName.set("CosmosPortalsLiveView")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
}

minecraft {
    mappings("official", "1.20.1")
}

dependencies {
    minecraft("net.minecraftforge:forge:1.20.1-47.3.0")
    compileOnly(files("libs/cosmos-library-1.20.1-10.6.0.0.jar"))
    compileOnly(files("libs/cosmosportals-1.20.1-7.5.0.0.jar"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
