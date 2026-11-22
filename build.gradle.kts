import io.papermc.paperweight.tasks.CreateBundlerJar
import io.papermc.paperweight.tasks.GenerateDevBundle
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    id("io.papermc.paperweight.patcher") version "2.0.0-beta.24"
}

paperweight {
    upstreams.paper {
        ref = providers.gradleProperty("paperRef")

        patchFile {
            path = "paper-server/build.gradle.kts"
            outputFile = file("archipelago-server/build.gradle.kts")
            patchFile = file("archipelago-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "paper-api/build.gradle.kts"
            outputFile = file("archipelago-api/build.gradle.kts")
            patchFile = file("archipelago-api/build.gradle.kts.patch")
        }
        patchDir("paperApi") {
            upstreamPath = "paper-api"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("archipelago-api/paper-patches")
            outputDir = file("paper-api")
        }
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

// "owner/repo" to publish to. GitHub Actions sets GITHUB_REPOSITORY, use -PgithubRepository=owner/repo elsewhere.
val githubRepository = providers.gradleProperty("githubRepository")
    .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
    .map { it.lowercase() } // GitHub Packages rejects uppercase owners
    .orNull

// Path in each archive -> file. Not META-INF/LICENSE, that is Mojang's in the server jar.
val licenseFiles = listOf("LICENSE", "NOTICE.md", "licenses/MIT.md")
    .associate { "META-INF/licenses/archipelago-paper/$it" to file(it) }

// For paperweight's archive tasks, which aren't Jar tasks and can't take extra files
class AddFilesToArchive(private val archive: Provider<RegularFile>, private val files: Map<String, File>) : Action<Task> {
    override fun execute(task: Task) {
        FileSystems.newFileSystem(archive.get().asFile.toPath()).use { zip ->
            for ((path, file) in files) {
                val target = zip.getPath(path)
                Files.createDirectories(target.parent)
                Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

subprojects {
    tasks.withType<Jar>().configureEach {
        for ((path, file) in licenseFiles) {
            from(file) { into(path.substringBeforeLast('/')) }
        }
    }
    // The Paperclip jar copies these from the bundler jar
    tasks.withType<CreateBundlerJar>().configureEach {
        inputs.files(licenseFiles.values).withPropertyName("licenseFiles").withPathSensitivity(PathSensitivity.RELATIVE)
        doLast(AddFilesToArchive(outputZip, licenseFiles))
    }
    tasks.withType<GenerateDevBundle>().configureEach {
        inputs.files(licenseFiles.values).withPropertyName("licenseFiles").withPathSensitivity(PathSensitivity.RELATIVE)
        doLast(AddFilesToArchive(devBundleFile, licenseFiles))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
        options.isFork = true
        options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
    }
    tasks.withType<Javadoc>().configureEach {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test>().configureEach {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
    }

    extensions.configure<PublishingExtension> {
        repositories {
            if (githubRepository != null) {
                maven("https://maven.pkg.github.com/$githubRepository") {
                    name = "githubPackages"
                    credentials(PasswordCredentials::class) // githubPackagesUsername and githubPackagesPassword
                }
            }
        }
    }
}