import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Mesma versão do app do PC: o atualizador compara com a última release do GitHub.
val versao = "1.3.2"

// A chave que assina o APK fica fora do repositório (o GitHub é público). Quem tiver a chave consegue publicar
// "atualizações" do app, e sem ela os aparelhos não aceitam atualizar por cima: guarde uma cópia.
val assinatura = Properties().apply {
    val f = file(System.getProperty("user.home") + "/.streamplay/assinatura.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.thenikito.streamplay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thenikito.streamplay"
        minSdk = 21
        targetSdk = 35
        val (a, b, c) = versao.split(".").map { it.toInt() }
        versionCode = a * 10000 + b * 100 + c
        versionName = versao
        // celulares e TVs Android são ARM; x86 só serviria para emulador e deixaria o APK bem maior
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            // gradlew -Pemulador: inclui x86 para testar no emulador do PC
            if (project.hasProperty("emulador")) abiFilters += listOf("x86", "x86_64")
        }
    }

    signingConfigs {
        if (assinatura.isNotEmpty()) create("release") {
            storeFile = file(assinatura.getProperty("storeFile"))
            storePassword = assinatura.getProperty("storePassword")
            keyAlias = assinatura.getProperty("keyAlias")
            keyPassword = assinatura.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // bibliotecas do VLC compactadas dentro do APK: o download fica bem menor
    packaging { jniLibs { useLegacyPackaging = true } }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.videolan.android:libvlc-all:3.6.5")

    testImplementation("junit:junit:4.13.2")
    // o android.jar só tem "esqueletos" do org.json; nos testes no PC entra a biblioteca de verdade
    testImplementation("org.json:json:20240303")
}
