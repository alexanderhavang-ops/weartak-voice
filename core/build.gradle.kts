plugins { `java-library` }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
tasks.test {
    providers.gradleProperty("mumbleTestDir").orNull?.let { systemProperty("mumble.test.dir", it) }
    providers.gradleProperty("mumbleTestPort").orNull?.let { systemProperty("mumble.test.port", it) }
}
