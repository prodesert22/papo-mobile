package chat.papo.mobile.security

/**
 * Os dados que o player web do YouTube deixa no aparelho em claro enquanto está aberto: o perfil do Chromium do `WebView`
 * (cookies, armazenamento, cache), na pasta de dados do app. É a única exceção à regra de que tudo o que o app guarda fica
 * cifrado, e só vale porque estes dados são apagados quando o player fecha e quando o app trava.
 */
fun interface WebPlayerData {
    /**
     * Fecha os players abertos e apaga os dados deles. Chamada quando a sessão fecha ([chat.papo.mobile.security.AppLock]); pode
     * vir de qualquer thread e não pode demorar nem lançar.
     */
    fun wipe()
}
