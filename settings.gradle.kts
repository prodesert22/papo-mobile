rootProject.name = "papo-mobile"

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Espelho oficial do Maven Central mantido pelo Google, com o mesmo conteúdo. O Maven
        // Central limita a taxa por IP, e os runners do GitHub compartilham IPs: o CI já falhou
        // com 429 baixando uma dependência nova. O espelho vem antes; o Maven Central fica de
        // reserva para o que ainda não chegou nele (o Gradle só passa adiante num 404).
        // O bloco pluginManagement roda isolado do resto do arquivo, por isso o endereço se repete.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirror" }
        mavenCentral()
        // Os plugins org.gradle.toolchains (foojay) só existem no Plugin Portal: vão direto para lá,
        // sem passar pelo espelho nem pelo Maven Central.
        exclusiveContent {
            forRepository { gradlePluginPortal() }
            filter { includeGroupByRegex("org\\.gradle\\.toolchains.*") }
        }
        gradlePluginPortal()
    }
}

plugins {
    // Permite ao Gradle baixar o JDK 21 quando a máquina não o tem instalado.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // O mesmo espelho do Maven Central do bloco pluginManagement, antes do Maven Central.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirror" }
        mavenCentral()
    }
}

include(":shared")
include(":androidApp")
