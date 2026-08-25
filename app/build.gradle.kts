import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val qrDecryptKeyHex = (localProperties.getProperty("qr.decrypt.key.hex") ?: "").trim()
val newsletterApiKey = (localProperties.getProperty("newsletter.api.key") ?: "").trim()
val newsletterApiEndpoint = (localProperties.getProperty("newsletter.api.endpoint") ?: "").trim()
val newsletterSpreadsheetApiKey = (localProperties.getProperty("newsletter.spreadshet.api.key") ?: "").trim()
val newsletterSpreadsheetApiEndpoint = (localProperties.getProperty("newsletter.spreadshet.api.endpoint") ?: "").trim()
val appVersionName = (localProperties.getProperty("version") ?: "").trim()
val versionMatch = Regex("^(\\d)\\.(\\d)\\.(\\d)$").matchEntire(appVersionName)
    ?: error("local.properties の version は X.Y.Z 形式（各1桁の数字）で指定してください。")
val appVersionCode = versionMatch.groupValues.drop(1).joinToString("").toInt()
require(appVersionCode > 0) {
    "local.properties の version は 0.0.0 以外を指定してください。"
}
fun envOrLocal(envName: String, localName: String): String =
    ((System.getenv(envName) ?: localProperties.getProperty(localName)) ?: "").trim()

val releaseKeystorePathFromConfig = envOrLocal("ANDROID_KEYSTORE_PATH", "android.keystore.path")
val releaseKeystoreBase64 = envOrLocal("ANDROID_KEYSTORE_BASE64", "android.keystore.base64")
val releaseStorePassword = envOrLocal("ANDROID_KEYSTORE_PASSWORD", "android.keystore.password")
val releaseKeyAlias = envOrLocal("ANDROID_KEY_ALIAS", "android.keystore.alias")
val releaseKeyPassword = envOrLocal("ANDROID_KEY_PASSWORD", "android.keystore.alias.password")
val generatedReleaseKeystoreFile = rootProject.layout.buildDirectory
    .file("generated/signing/release-keystore.jks")
    .get()
    .asFile
val releaseKeystorePath = when {
    releaseKeystorePathFromConfig.isNotBlank() -> releaseKeystorePathFromConfig
    releaseKeystoreBase64.isNotBlank() -> {
        generatedReleaseKeystoreFile.parentFile.mkdirs()
        generatedReleaseKeystoreFile.writeBytes(Base64.getDecoder().decode(releaseKeystoreBase64))
        generatedReleaseKeystoreFile.absolutePath
    }
    else -> ""
}
val releaseSigningConfigured = listOf(
    releaseKeystorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all(String::isNotBlank)
val requestedTasks = gradle.startParameter.taskNames.map { it.substringAfterLast(':').lowercase() }
val releaseSigningRequired = requestedTasks.any {
    "release" in it || it in setOf("assemble", "build", "bundle")
}

if (releaseSigningRequired && !releaseSigningConfigured) {
    error(
        "リリースビルドには ANDROID_KEYSTORE_PATH または android.keystore.path / android.keystore.base64、" +
            "ANDROID_KEYSTORE_PASSWORD または android.keystore.password、" +
            "ANDROID_KEY_ALIAS または android.keystore.alias、" +
            "ANDROID_KEY_PASSWORD または android.keystore.alias.password の設定が必要です。",
    )
}

android {
    namespace = "com.ttqr.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ttqr.android"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "QR_DECRYPT_KEY_HEX", "\"$qrDecryptKeyHex\"")
        buildConfigField("String", "NEWSLETTER_API_KEY", "\"$newsletterApiKey\"")
        buildConfigField("String", "NEWSLETTER_API_ENDPOINT", "\"$newsletterApiEndpoint\"")
        buildConfigField("String", "NEWSLETTER_SPREADSHET_API_KEY", "\"$newsletterSpreadsheetApiKey\"")
        buildConfigField("String", "NEWSLETTER_SPREADSHET_API_ENDPOINT", "\"$newsletterSpreadsheetApiEndpoint\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    val cameraXVersion = "1.4.2"

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")
    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("androidx.camera:camera-mlkit-vision:$cameraXVersion")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
