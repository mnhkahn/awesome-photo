import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val releaseKeystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
val releaseVersionName = providers.gradleProperty("releaseVersionName").orElse("1.0")
val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orElse("1")
val appUpdateUrl = providers.gradleProperty("appUpdateUrl").orElse("https://www.cyeam.com/api/apps/awesome-photo/update")

// Content-based cache identity: stable across machines/builds, changes with analysis inputs.
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
val analysisSources = listOf("ModelCatalog.kt", "ModelDownloadStore.kt", "ModelRepository.kt", "AestheticCalibration.kt", "WallpaperAssessment.kt", "AestheticAnalyzer.kt", "PhotoScorer.kt", "EdgeSharpness.kt", "PhotoCandidate.kt", "SegFormerAnalyzer.kt", "FolderScanner.kt")
val analysisFingerprint = sha256((analysisSources.joinToString("\n") { name ->
    val source = layout.projectDirectory.file("src/main/java/com/awesomephoto/model/$name")
    "$name:${sha256(providers.fileContents(source).asBytes.get())}"
}).toByteArray(Charsets.UTF_8))

val prepareLightAssets by tasks.registering(Sync::class) {
    from("src/main/assets") { exclude("**/*.onnx") }
    into(layout.buildDirectory.dir("generated/light-assets"))
    doFirst {
        check(file("src/main/assets/models.json").isFile) {
            "Missing model catalog. Run: python tools/package_models.py --base-url <release-assets-url>"
        }
    }
}
tasks.named("preBuild") { dependsOn(prepareLightAssets) }

android {
    sourceSets.getByName("main").assets.setSrcDirs(listOf(layout.buildDirectory.dir("generated/light-assets")))
    namespace = "com.awesomephoto"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.awesomephoto"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode.get().toInt()
        versionName = releaseVersionName.get()

        buildConfigField("String", "ANALYSIS_FINGERPRINT", "\"$analysisFingerprint\"")

        buildConfigField("String", "APP_UPDATE_URL", "\"${appUpdateUrl.get()}\"")

    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (releaseKeystoreFile != null) {
            create("release") {
                storeFile = file(releaseKeystoreFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.onnxruntime.android)
    implementation(libs.coil.compose)
    debugImplementation("androidx.compose.ui:ui-tooling")
}
