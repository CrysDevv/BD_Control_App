import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

// Lee las llaves criptográficas desde local.properties (nunca desde un archivo versionado).
val secrets = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "cl.avelyn.bd_control_app"
    compileSdk = 35 // la librería usb-serial-for-android se compila con 35

    defaultConfig {
        applicationId = "cl.avelyn.bd_control_app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "AES_KEY_HEX", "\"${secrets.getProperty("aes.key", "")}\"")
        // "wifi" (ESP32 reales, por defecto) o "supabase" (simulador sin hardware)
        // false (por defecto) = no retener el MulticastLock del Wi-Fi (ahorra batería); true solo si el teléfono no recibe al relé
        buildConfigField("boolean", "WIFI_MULTICAST_LOCK", secrets.getProperty("iot.multicastLock", "false"))
        // true = también escucha al sensor conectado por el cable USB-C (ver SerialLink)
        buildConfigField("boolean", "SERIAL_ENABLED", secrets.getProperty("iot.serial", "true"))
        buildConfigField("String", "IOT_TRANSPORT", "\"${secrets.getProperty("iot.transport", "wifi")}\"")
        buildConfigField("String", "SUPABASE_URL", "\"${secrets.getProperty("supabase.url", "")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secrets.getProperty("supabase.anon.key", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation(libs.firebase.auth)
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")

    // ViewModel y ciclo de vida
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Navegación
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // Room + SQLCipher
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Corrutinas
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))
    implementation("com.google.firebase:firebase-database")

    // Supabase y Ktor con OkHttp (Soporte WebSocket habilitado)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation("io.ktor:ktor-client-okhttp:3.0.0")
    implementation(libs.kotlinx.serialization.json)

    // Comunicación serial por USB (OTG) con el ESP32 del sensor; se descarga de JitPack (ver settings.gradle.kts)
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")

    // Pruebas
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}