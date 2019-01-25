import com.jfrog.bintray.gradle.BintrayExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.*

val local = Properties()
val localProperties: java.io.File = rootProject.file("local.properties")
if (localProperties.exists()) {
    localProperties.inputStream().use { local.load(it) }
}

plugins {
    kotlin("jvm") version "1.3.20"
    id("com.jfrog.bintray") version "1.8.3"
    `maven-publish`
    id("org.jetbrains.dokka") version "0.9.17"
    application
}

defaultTasks("clean", "build")

group = "com.github.mvysny.sucklessprofiler"
version = "0.5-SNAPSHOT"

application {
    mainClassName = "com.github.mvysny.sucklessprofiler.SucklessProfilerKt"
}

repositories {
    mavenCentral()
    maven { setUrl("https://dl.bintray.com/mvysny/github") }
}

dependencies {
    compile(kotlin("stdlib-jdk8"))
    testCompile("com.github.mvysny.dynatest:dynatest:0.13")
}

val sourceJar = task("sourceJar", Jar::class) {
    dependsOn(tasks["classes"])
    classifier = "sources"
    from(sourceSets.main.get().allSource)
}

val javadocJar = task("javadocJar", Jar::class) {
    val javadoc = tasks["dokka"] as DokkaTask
    javadoc.outputFormat = "javadoc"
    javadoc.outputDirectory = "$buildDir/javadoc"
    dependsOn(javadoc)
    classifier = "javadoc"
    from(javadoc.outputDirectory)
}

publishing {
    publications {
        create("mavenJava", MavenPublication::class.java).apply {
            groupId = project.group.toString()
            this.artifactId = "suckless-profiler"
            version = project.version.toString()
            pom {
                description.set("An embedded profiler which you start and stop on will, and it will dump the profiling info into the console")
                name.set("Suckless ASCII Profiler")
                url.set("https://github.com/mvysny/suckless-ascii-profiler")
                licenses {
                    license {
                        name.set("The Apache 2.0 License")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("mavi")
                        name.set("Martin Vysny")
                        email.set("martin@vysny.me")
                    }
                }
                scm {
                    url.set("https://github.com/mvysny/suckless-ascii-profiler")
                }
            }
            from(components["java"])
            artifact(sourceJar)
            artifact(javadocJar)
        }
    }
}

bintray {
    user = local.getProperty("bintray.user")
    key = local.getProperty("bintray.key")
    pkg(closureOf<BintrayExtension.PackageConfig> {
        repo = "github"
        name = "com.github.mvysny.sucklessprofiler"
        setLicenses("Apache-2.0")
        vcsUrl = "https://github.com/mvysny/suckless-ascii-profiler"
        publish = true
        setPublications("mavenJava")
        version(closureOf<BintrayExtension.VersionConfig> {
            this.name = project.version.toString()
            released = Date().toString()
        })
    })
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        // to see the exceptions of failed tests in the CI console.
        exceptionFormat = TestExceptionFormat.FULL
    }
}
