plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "chat.papo.mobile.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        androidResources { enable = true }
        withHostTest { }
    }

    // Declarados desde já: com mais de um alvo o commonMain só compila contra a
    // biblioteca comum (sem APIs de Android/Java). A compilação nativa em si
    // exige macOS e fica desativada no Linux.
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        optIn.add("kotlin.io.encoding.ExperimentalEncodingApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.icons)
            implementation(libs.compose.resources)
            implementation(libs.navigation.compose)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.datetime)
            implementation(libs.kotlinx.io)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serialization.json)
            implementation(libs.sqldelight.coroutines)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.turbine)
        }
        androidMain.dependencies {
            implementation(libs.coroutines.android)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.biometric)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.sqlcipher.android)
            implementation(libs.webrtc.android)
            implementation(libs.androidx.fragment)
            implementation(libs.androidx.sqlite)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.jvm.driver)
        }
    }
}

sqldelight {
    databases {
        create("RegistryDb") {
            dialect(libs.sqldelight.dialect)
            packageName.set("chat.papo.mobile.data.db.registry")
            srcDirs.setFrom("src/commonMain/sqldelight/registry")
        }
        create("AccountDb") {
            dialect(libs.sqldelight.dialect)
            packageName.set("chat.papo.mobile.data.db.account")
            srcDirs.setFrom("src/commonMain/sqldelight/account")
        }
    }
}

compose.resources {
    packageOfResClass = "chat.papo.mobile.resources"
}
