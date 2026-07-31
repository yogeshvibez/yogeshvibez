plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.heyogesh.drive"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.heyogesh.drive"
        // MediaStore Downloads with safe scoped-storage resume support requires API 29.
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"
        buildConfigField("String", "STORAGE_BASE_URL", "\"https://storage.heyogesh.dpdns.org\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    signingConfigs {
        create("release") {
            // Values come from gradle.properties or CI secrets; no signing secret belongs in Git.
            val storePath = providers.gradleProperty("HEYOGESH_DRIVE_KEYSTORE").orNull
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = providers.gradleProperty("HEYOGESH_DRIVE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("HEYOGESH_DRIVE_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("HEYOGESH_DRIVE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
}
