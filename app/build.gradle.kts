import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.navigation.safeargs)
    alias(libs.plugins.kotlin.serialization)
}

// local.properties no se versiona: solo existe en la máquina de cada desarrollador.
// Si falta, el build debug debe seguir funcionando; lo único que queda deshabilitado
// es la firma de release.
val localProps = Properties().apply {
    val fichero = rootProject.file("local.properties")
    if (fichero.exists()) fichero.inputStream().use { load(it) }
}

/** Claves de firma leídas de local.properties. Null si la clave no está definida. */
val clavesFirma = listOf("KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    .associateWith { localProps[it] as String? }

/** true solo si las cuatro claves están presentes y no vacías. */
val hayFirmaRelease = clavesFirma.values.all { !it.isNullOrBlank() }

// Room exporta el esquema a app/schemas/ (exportSchema = true en SmsDatabase). Sin este
// argumento el procesador no sabe dónde escribirlo y solo emite un warning, que es lo que
// venía pasando. Los JSON generados se versionan: son la referencia para escribir migraciones.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.capicua.smstotelegram"
    compileSdk = 37

    signingConfigs {
        if (hayFirmaRelease) {
            create("release") {
                storeFile = file(clavesFirma["KEYSTORE_PATH"]!!)
                storePassword = clavesFirma["KEYSTORE_PASSWORD"]
                keyAlias = clavesFirma["KEY_ALIAS"]
                keyPassword = clavesFirma["KEY_PASSWORD"]
            }
        }
    }

    defaultConfig {
        applicationId = "com.capicua.smstotelegram"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Las reglas de reenvío y los ajustes se configuran en la app, no en el build.
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Sin claves en local.properties el APK sale sin firmar, pero el build no rompe.
            if (hayFirmaRelease) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// El APK de release se llama smstotelegram-<versionName>.apk. AGP 9 retiró la API de
// variantes antigua (applicationVariants) y la nueva no expone el nombre del fichero,
// así que se recurre a VariantOutputImpl, que es interna: si una versión futura de
// AGP la cambia, el síntoma es que el APK vuelve a llamarse app-release.apk.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { salida ->
            (salida as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName?.set("smstotelegram-${salida.versionName.orNull}.apk")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.navigation.fragment)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager
    implementation(libs.work.runtime)
    implementation(libs.kotlinx.coroutines.guava)

    // DataStore
    implementation(libs.datastore.preferences)

    // kotlinx.serialization — exportar e importar el juego de reglas en JSON
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Timber
    implementation(libs.timber)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
