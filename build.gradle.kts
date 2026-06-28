import com.android.build.api.dsl.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.0.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
        classpath("com.github.recloudstream:gradle:81b1d424d2")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: LibraryExtension.() -> Unit) {
    extensions.getByName<LibraryExtension>("android").apply {
        configuration()
    }
}

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            freeCompilerArgs.addAll(
                "-Xno-call-assertions",
                "-Xno-param-assertions",
                "-Xno-receiver-assertions",
                "-Xannotation-default-target=param-property"
            )
        }
    }

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/digitalus00/DigitalExtensions")
        authors = listOf("Digitalus00")
        status = 1
        requiresResources = false
    }

    android {
        namespace = "com.Digital.${project.name.lowercase().replace("-", "_").let { if (it.firstOrNull()?.isDigit() == true) "p$it" else it }}"
        compileSdk = 36

        defaultConfig {
            minSdk = 21
        }

        lint {
            targetSdk = 36
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }

    dependencies {
        // CloudStream stubs
        add("cloudstream", "com.lagradost:cloudstream3:pre-release")

        // All compileOnly — CloudStream already bundles these
        add("compileOnly", kotlin("stdlib"))
        add("compileOnly", "com.github.Blatzar:NiceHttp:0.4.13")
        add("compileOnly", "org.jsoup:jsoup:1.22.1")
        add("compileOnly", "com.fasterxml.jackson.module:jackson-module-kotlin:2.13.5")
        add("compileOnly", "com.fasterxml.jackson.core:jackson-databind:2.13.5")
        add("compileOnly", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        add("compileOnly", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        add("compileOnly", "org.mozilla:rhino:1.9.0")
        add("compileOnly", "me.xdrop:fuzzywuzzy:1.4.0")
        add("compileOnly", "com.google.code.gson:gson:2.13.2")
        add("compileOnly", "app.cash.quickjs:quickjs-android:0.9.2")
        add("compileOnly", "com.github.vidstige:jadb:v1.2.1")
    }
}

// derle = selective build (only status=1 plugins)
tasks.register("derle") {
    group = "help"
    doLast {
        println("Filtreleme modu aktif: status=1 olanlar disindaki eklentiler derleme disi birakildi.")
    }
}

gradle.taskGraph.whenReady {
    if (hasTask(":derle")) {
        allTasks.forEach { task ->
            if (task.project != rootProject) {
                val csExt = task.project.extensions.findByType<CloudstreamExtension>()
                if (csExt != null && csExt.status != 1) {
                    task.enabled = false
                }
            }
        }
    }
}