plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aegis.vpnclient"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aegis.vpnclient"
        minSdk = 24          // VpnService is stable from API 21+; 24 keeps things simple
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // Injected at build time so you never hardcode your backend URL in source.
        // Override with: ./gradlew assembleRelease -PapiBaseUrl=https://vpn-api.yourdomain.com
        buildConfigField("String", "API_BASE_URL", "\"${project.findProperty("apiBaseUrl") ?: "https://admin.repairdock.online:9443"}\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Secure local storage for the session token
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coroutines for async network + VPN lifecycle work
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Xray-core Android bindings. This AAR is NOT on Maven Central — build it
    // yourself from https://github.com/2dust/AndroidLibXrayLite with Go +
    // gomobile (`gomobile bind -androidapi 24 -o libv2ray.aar ./`), or take a
    // libv2ray.aar out of a trusted v2rayNG release, and drop it in app/libs/.
    implementation(files("libs/libv2ray.aar"))
}
