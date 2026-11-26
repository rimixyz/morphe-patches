extension {
    name = "extensions/poco-launcher.mpe"
}

android {
    namespace = "app.rimixyz.extension.pocolauncher"

    defaultConfig {
        minSdk = 34
    }
}

val compilePocoStubs = tasks.register<JavaCompile>("compilePocoStubs") {
    source = fileTree("src/stubs/java")
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("stubs/classes"))
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
}

val pocoStubsJar = tasks.register<Jar>("pocoStubsJar") {
    dependsOn(compilePocoStubs)
    from(compilePocoStubs.map { it.destinationDirectory })
    destinationDirectory.set(layout.buildDirectory.dir("stubs/libs"))
    archiveFileName.set("poco-stubs.jar")
}

configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains", module = "annotations")
}

dependencies {
    compileOnly(files(pocoStubsJar))
}
