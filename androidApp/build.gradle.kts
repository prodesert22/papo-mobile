import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.aboutlibraries.android)
}

kotlin {
    jvmToolchain(21)
}

// O push usa o projeto Firebase do app, descrito em `androidApp/google-services.json` (fora do git: ver .gitignore). Quem
// compila sem o arquivo continua com um app que funciona, só sem push; por isso o plugin só entra quando o arquivo existe.
// O arquivo é lido por `providers.fileContents`, e não por `File.exists()`, para o cache de configuração notar quando ele
// aparece ou some; o plugin precisa ser aplicado aqui, na configuração, antes do bloco `android`.
val hasGoogleServices = providers.fileContents(layout.projectDirectory.file("google-services.json")).asBytes.isPresent
if (hasGoogleServices) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
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

// Licenças de terceiros: o plugin lê o grafo real de dependências do APK e gera o JSON que a tela mostra. `offlineMode`
// proíbe qualquer acesso à rede na compilação (nem licença remota, nem texto SPDX); por isso o texto das licenças e o que o
// POM não informa entram à mão em `aboutlibraries/licenses`.
aboutLibraries {
    offlineMode = true
    collect {
        configPath = file("aboutlibraries")
    }
    // Modo estrito: uma dependência nova com licença fora desta lista (ou sem licença) derruba a compilação, e alguém
    // confere o que ela exige antes de ela entrar no APK.
    license {
        strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
        // A licença do SQLCipher não tem identificador SPDX: entra pelo nome que `aboutlibraries/licenses/sqlcipher.json` lhe dá.
        allowedLicenses.addAll("Apache-2.0", "MIT", "BSD-3-Clause", "ASDKL", "SQLCipher Community Edition License")
    }
}

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
    // O serviço que recebe o token e as mensagens do FCM mora aqui, junto do manifesto que o declara.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)

    // Testes que só fazem sentido num aparelho: Keystore de verdade e SQLCipher nativo.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.sqldelight.android.driver)
    androidTestImplementation(libs.accompanist.drawablepainter)
    // A fonte de dados do player (PapoDataSourceTest): Media3 de compilação, um motor HTTP simulado
    // e o OkHttp de produção para os testes com socket de verdade.
    androidTestImplementation(libs.media3.exoplayer)
    androidTestImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.ktor.client.okhttp)
}
