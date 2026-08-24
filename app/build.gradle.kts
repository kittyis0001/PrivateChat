plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.privatechat.app"
    compileSdk = 34

    defaultConfig {
        buildConfigField("String","FIREBASE_DATABASE_URL","\"https://private-chat-318a6-default-rtdb.asia-southeast1.firebasedatabase.app\"")
        manifestPlaceholders["firebaseDatabaseUrl"] = "https://private-chat-318a6-default-rtdb.asia-southeast1.firebasedatabase.app"
        // Filled in after deploying backend/ to Render — see
        // backend/README.md step 5. Placeholder value is intentionally
        // non-functional (Retrofit will fail closed with a network
        // error, not silently succeed) until it's set for real.
       buildConfigField("String", "BACKEND_BASE_URL", "\"https://privatechat-u3qz.onrender.com/\"")
        // Must match the API_SECRET env var set in Render's dashboard
        // exactly. Never the Firebase service account itself — that
        // stays server-side only, per backend/services/firebase.js.
        buildConfigField("String", "BACKEND_API_SECRET", "\"kitty_chat_2026_secret_x9K2_p9Lm\"")
        // Cloudinary unsigned upload — a cloud name + unsigned upload
        // preset are safe to ship in the app (unlike an API secret):
        // Cloudinary's own dashboard scopes exactly what an unsigned
        // preset is allowed to do, so no backend call is needed for
        // this (and the Render backend, explicitly out of scope for
        // this feature, was never touched). Create both at
        // https://cloudinary.com/console/settings/upload — an unsigned
        // preset under Upload presets — then replace these two values.
        buildConfigField("String", "CLOUDINARY_CLOUD_NAME", "\"REPLACE-ME\"")
        buildConfigField("String", "CLOUDINARY_UPLOAD_PRESET", "\"REPLACE-ME\"")
        applicationId = "com.privatechat.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    buildFeatures {
        buildConfig = true
        viewBinding = true
        dataBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // System emoji picker UI for the tap-to-react "+" button, with a
    // BottomSheet fallback (ChatActivity checks isAvailable() and only
    // uses this on devices where it actually renders emoji content).
    implementation("androidx.emoji2:emoji2-emojipicker:1.6.0")

    // Firebase — versions managed by BoM, matches "use Firebase BoM" requirement
    implementation(platform("com.google.firebase:firebase-bom:32.7.4"))
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-storage-ktx")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Calling the Render notification backend, and queuing/retrying
    // that call reliably across app restarts and connectivity loss.
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Profile photo loading (circleCrop for avatars, memory-safe
    // caching) and the Instagram-style full-screen viewer.
    implementation("com.github.bumptech.glide:glide:4.16.0")
    // Modern in-app photo picker — needs no storage/gallery permission
    // at all on API 30+ (backed by Google Play services) and API 33+
    // natively; the explicit READ_MEDIA_IMAGES/READ_EXTERNAL_STORAGE
    // request in AndroidManifest.xml covers the older-API fallback
    // path this pulls in.
    implementation("androidx.activity:activity-ktx:1.9.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
