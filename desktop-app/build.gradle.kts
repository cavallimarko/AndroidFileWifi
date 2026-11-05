import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)

    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.json)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.jmdns)
    implementation(libs.zxing.core)
    implementation(libs.slf4j.simple)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnit() }

compose.desktop {
    application {
        mainClass = "hr.gd.androidfilewifi.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "AndroidFileWifi"
            packageVersion = "1.0.0"
            description = "Receives photos and videos from your Android phone over Wi-Fi"
            vendor = "GD Entertainment"
            modules("java.naming", "jdk.crypto.ec", "java.sql", "jdk.unsupported")
            windows {
                menuGroup = "AndroidFileWifi"
                shortcut = true
                dirChooser = true
                perUserInstall = true
            }
        }
    }
}
