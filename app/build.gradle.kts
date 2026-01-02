import org.lineageos.generatebp.GenerateBpPluginExtension
import org.lineageos.generatebp.models.Module
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.lineageos.generatebp)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(keystorePropertiesFile.inputStream())
    }
}

android {
    compileSdk = 36
    namespace = "net.pixelos.ota"

    defaultConfig {
        applicationId = "net.pixelos.ota"
        minSdk = 34
        targetSdk = compileSdk
        versionCode = 1
        versionName = "1.0"
    }

    sourceSets {
        getByName("main") {
            val settingsLib = file("../../../../frameworks/base/packages/SettingsLib")
            val parentRes = File(settingsLib, "res")
            val subModuleRes = settingsLib.listFiles()
                ?.filter { it.isDirectory }
                ?.map { File(it, "res") }
                ?: emptyList()
            val allExternalRes = (subModuleRes + parentRes).filter { it.exists() }
            res.srcDirs(allExternalRes)
            val subModuleJava = settingsLib.listFiles()
                ?.filter { it.isDirectory }
                ?.map { File(it, "src") }
                ?: emptyList()
            val parentJava = File(settingsLib, "src")
            val allExternalJava = (subModuleJava + parentJava).filter { it.exists() }
            java.srcDirs(allExternalJava)
        }
    }

    buildTypes {
        getByName("release") {
            // Includes the default ProGuard rules files.
            setProguardFiles(
                listOf(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            )
        }
        getByName("debug") {
            // Append .dev to package name so we won't conflict with AOSP build.
            applicationIdSuffix = ".dev"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("release") {
            (keystoreProperties["keyAlias"] as String?)?.let {
                keyAlias = it
            }
            (keystoreProperties["keyPassword"] as String?)?.let {
                keyPassword = it
            }
            (keystoreProperties["storeFile"] as String?)?.let {
                storeFile = file(it)
            }
            (keystoreProperties["storePassword"] as String?)?.let {
                storePassword = it
            }
        }
    }
}

dependencies {
    compileOnly(fileTree(mapOf("dir" to "../system_libs", "include" to listOf("*.jar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.localbroadcastmanager)
    implementation(libs.androidx.preference)
    implementation(libs.material)
    implementation(libs.core)
    implementation(libs.androidx.swiperefreshlayout)
}

configure<GenerateBpPluginExtension> {
    targetSdk.set(android.defaultConfig.targetSdk!!)
    minSdk.set(android.defaultConfig.minSdk!!)
    availableInAOSP.set { module: Module ->
        when {
            module.group.startsWith("androidx") -> true
            module.group.startsWith("org.jetbrains") -> true
            module.group == "com.google.android.material" -> true
            module.group == "com.google.errorprone" -> true
            module.group == "com.google.guava" -> true
            module.group == "junit" -> true
            else -> false
        }
    }
}
