// Proves that the storage API is enough to write a world format without NMS, so only depend on the API.
// Not shipped or published. It does nothing unless enabled, see StorageTestBootstrap.

dependencies {
    compileOnly(project(":archipelago-api"))
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "apiVersion" to rootProject.providers.gradleProperty("apiVersion").get(),
    )
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}
