import java.util.Properties
import javax.inject.Inject

plugins {
    id("com.android.application")
}

/** Unpacks the Mozilla-signed uBlock Origin Lite .xpi into the APK's assets as a built-in extension. */
abstract class UnpackExtension : DefaultTask() {
    @get:InputFile abstract val xpi: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations
    @get:Inject abstract val archives: ArchiveOperations

    @TaskAction
    fun unpack() {
        fs.sync {
            from(archives.zipTree(xpi))
            into(outputDir.dir("extensions/ubol"))
        }
    }
}

val unpackUblock = tasks.register<UnpackExtension>("unpackUblock") {
    xpi.set(layout.projectDirectory.file("ublock/ubol.firefox.signed.xpi"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(unpackUblock, UnpackExtension::outputDir)
    }
}

val geckoviewVersion: String = providers.gradleProperty("geckoviewVersion").get()
val tveyeVersionCode: Int = providers.gradleProperty("tveyeVersionCode").get().toInt()
val tveyeUpdateRepo: String = providers.gradleProperty("tveyeUpdateRepo").getOrElse("")

// Signing: a local file outside the repo (never committed), or environment variables in CI.
val signing = Properties().apply {
    val f = rootProject.file("../tveye-secrets/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String, env: String): String? = signing.getProperty(key) ?: System.getenv(env)

android {
    namespace = "app.tveye"
    // GeckoView 160 and androidx.core 1.19 compile against API 37.2. This does not change which TVs it runs on
    // (minSdk) or the runtime behaviour (targetSdk).
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "app.tveye"
        minSdk = 26
        targetSdk = 36
        versionCode = tveyeVersionCode
        versionName = "1.$tveyeVersionCode (Gecko ${geckoviewVersion.substringBefore('.')})"
        buildConfigField("String", "UPDATE_REPO", "\"$tveyeUpdateRepo\"")
        buildConfigField("String", "GECKO_VERSION", "\"$geckoviewVersion\"")
        ndk { abiFilters += "armeabi-v7a" }
    }

    signingConfigs {
        create("release") {
            val store = secret("storeFile", "TVEYE_KEYSTORE")
            if (store != null) {
                storeFile = file(store)
                storePassword = secret("storePassword", "TVEYE_STORE_PASSWORD")
                keyAlias = secret("keyAlias", "TVEYE_KEY_ALIAS")
                keyPassword = secret("keyPassword", "TVEYE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    androidResources {
        // The default asset filter drops directories starting with "_", which removes the extension's _locales and makes
        // Gecko reject the extension as invalid. Keep the default's other exclusions.
        ignoreAssetsPatterns += listOf("!.svn", "!.git", "!.ds_store", "!*.scc", "!CVS", "!thumbs.db", "!picasa.ini", "!*~")
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview-armeabi-v7a:$geckoviewVersion")
    // GeckoView already ships these at runtime; listed so our code can compile against them.
    implementation("androidx.annotation:annotation:1.11.0")
    implementation("androidx.core:core:1.19.1")
}
