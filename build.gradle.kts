plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 compila Kotlin por sí mismo (Kotlin integrado) y ya no admite el plugin
    // kotlin-android. Declarar aquí el de serialización pone en el classpath el KGP de
    // libs.versions.toml, que es el que usa AGP en lugar del que trae de serie.
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.navigation.safeargs) apply false
}
