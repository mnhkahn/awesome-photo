plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val releaseKeystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
val releaseVersionName = providers.gradleProperty("releaseVersionName").orElse("1.0")
val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orElse("1")
val pgyerDownloadPage = providers.gradleProperty("pgyerDownloadPage").orElse("")

val verifyModelAsset by tasks.registering {
    val model = layout.projectDirectory.file("src/main/assets/segformer_b0_ade512_int8.onnx")
    doLast {
        check(model.asFile.isFile && model.asFile.length() > 0) {
            "Missing or empty SegFormer model. Run: python tools/export_android_model.py --download"
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyModelAsset) }

android {
    namespace = "com.awesomephoto"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.awesomephoto"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode.get().toInt()
        versionName = releaseVersionName.get()

        // This is a public page, never an API key. Empty for local/debug builds.
        buildConfigField("String", "PGYER_DOWNLOAD_PAGE", "\"${pgyerDownloadPage.get()}\"")
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
