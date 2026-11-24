import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
}

// A versão do app vem de `papo.version` (gradle.properties): major.minor.patch, com
// `-beta.N` opcional. `tools/release.py` aplica a mesma regra; o CI confere se os dois batem.
val papoVersion: String = providers.gradleProperty("papo.version").get().trim()
val papoVersionCode: Int = run {
    val match = Regex("""(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?""").matchEntire(papoVersion)
        ?: error("papo.version inválida: '$papoVersion' (use 1.0.1 ou 1.0.1-beta.1)")
    val (major, minor, patch) = match.destructured.toList().take(3).map(String::toInt)
    val beta = match.groupValues[4].toIntOrNull()
    require(minor <= 99 && patch <= 99 && (beta == null || beta in 1..98)) { "papo.version fora dos limites: '$papoVersion'" }
    // Betas ficam abaixo da final da mesma versão, para o Android aceitar a final por cima.
    major * 1_000_000 + minor * 10_000 + patch * 100 + (beta ?: 99)
}

// Assinatura de release: variáveis de ambiente no CI, `keystore.properties` (fora do git)
// na máquina de quem lança. Sem nenhuma das duas, o APK de release sai sem assinatura.
val releaseSigning: Map<String, String>? = run {
    val env = listOf("PAPO_KEYSTORE_FILE", "PAPO_KEYSTORE_PASSWORD", "PAPO_KEY_ALIAS", "PAPO_KEY_PASSWORD")
        .map { providers.environmentVariable(it).orNull }
    if (env.all { !it.isNullOrBlank() }) {
        return@run mapOf("storeFile" to env[0]!!, "storePassword" to env[1]!!, "keyAlias" to env[2]!!, "keyPassword" to env[3]!!)
    }
    val text = providers.fileContents(rootProject.layout.projectDirectory.file("keystore.properties")).asText.orNull
        ?: return@run null
    val properties = Properties().apply { load(text.reader()) }
    val keys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    if (keys.any { properties.getProperty(it).isNullOrBlank() }) null else keys.associateWith { properties.getProperty(it) }
}

// Um APK por arquitetura mais o universal. Só nas releases (-Ppapo.abiSplits=true):
// no dia a dia o APK de debug continua único e com o nome de sempre.
val abiSplits = providers.gradleProperty("papo.abiSplits").orNull == "true"

android {
    namespace = "chat.papo.mobile"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "chat.papo.mobile"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = papoVersionCode
        versionName = papoVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    // Keystore de debug fixa no projeto: a padrão fica em ~/.android e, se for
    // recriada, o Android recusa atualizar o app já instalado.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        releaseSigning?.let { signing ->
            create("release") {
                storeFile = rootProject.file(signing.getValue("storeFile"))
                storePassword = signing.getValue("storePassword")
                keyAlias = signing.getValue("keyAlias")
                keyPassword = signing.getValue("keyPassword")
            }
        }
    }

    splits {
        abi {
            isEnable = abiSplits
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.coroutines.android)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)

    // Testes que só fazem sentido num aparelho: Keystore de verdade e SQLCipher nativo.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.sqldelight.android.driver)
}
