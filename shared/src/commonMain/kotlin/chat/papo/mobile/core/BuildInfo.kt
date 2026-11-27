package chat.papo.mobile.core

/**
 * A compilação instalada: [versionName] é o `papo.version` (`X.Y.Z`, com `-beta.N` opcional), [versionCode] o número que
 * o Android compara nas atualizações e [debuggable] diz se é uma compilação de desenvolvimento.
 */
data class BuildInfo(
    val versionName: String = "",
    val versionCode: Long = 0,
    val debuggable: Boolean = false,
)
