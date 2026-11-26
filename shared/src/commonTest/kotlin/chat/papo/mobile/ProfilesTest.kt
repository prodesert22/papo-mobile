package chat.papo.mobile

import chat.papo.mobile.chat.Presence
import chat.papo.mobile.chat.ProfileStamp
import chat.papo.mobile.chat.User
import chat.papo.mobile.chat.UserProfile
import chat.papo.mobile.chat.avatarMediaName
import chat.papo.mobile.chat.profileHeader
import chat.papo.mobile.chat.profileStamp
import chat.papo.mobile.chat.toProfile
import chat.papo.mobile.data.api.RoleSummaryDto
import chat.papo.mobile.data.api.UserProfileDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ProfilesTest {
    private val sha = "a".repeat(64)

    private fun dto(
        nickname: String? = null,
        description: String? = null,
        banner: String? = null,
        statusMessage: String? = null,
        createdAt: String? = "2026-01-04T10:30:00Z",
        roles: List<RoleSummaryDto> = emptyList(),
    ) = UserProfileDto(
        id = "u1",
        username = "ana",
        nickname = nickname,
        avatarBlob = "AAAA",
        bannerMedia = banner,
        description = description,
        statusMessage = statusMessage,
        createdAt = createdAt,
        roles = roles,
    )

    private fun user(nickname: String? = null, statusMessage: String? = null, avatarVersion: Long = 0) = User(
        id = "u1",
        username = "ana",
        nickname = nickname,
        statusMessage = statusMessage,
        typingPhrase = null,
        banned = false,
        roleColor = null,
        avatarVersion = avatarVersion,
    )

    private fun profile(nickname: String? = null, statusMessage: String? = null) = UserProfile(
        id = "u1",
        username = "ana",
        nickname = nickname,
        description = null,
        bannerSha = null,
        statusMessage = statusMessage,
        memberSince = null,
        roles = emptyList(),
    )

    // toProfile

    @Test
    fun perfilCopiaOsCamposEOsCargosNaOrdemDoServidor() {
        val roles = listOf(RoleSummaryDto("r1", "Moderação", "#E86CA5"), RoleSummaryDto("r2", "Leitores", null))

        val profile = dto(nickname = "Ana Lima", description = "Gosto de café", banner = sha, statusMessage = "Em reunião", roles = roles).toProfile()

        assertEquals("u1", profile.id)
        assertEquals("ana", profile.username)
        assertEquals("Ana Lima", profile.nickname)
        assertEquals("Gosto de café", profile.description)
        assertEquals(sha, profile.bannerSha)
        assertEquals("Em reunião", profile.statusMessage)
        assertEquals(Instant.parse("2026-01-04T10:30:00Z"), profile.memberSince)
        assertEquals(roles, profile.roles)
    }

    @Test
    fun textosEmBrancoViramAusentesEOsOutrosSaemSemAsPontas() {
        val profile = dto(nickname = "   ", description = "  \n ", statusMessage = "", banner = null).toProfile()

        assertNull(profile.nickname)
        assertNull(profile.description)
        assertNull(profile.statusMessage)
        assertNull(profile.bannerSha)
        assertEquals("linha 1\nlinha 2", dto(description = "  linha 1\nlinha 2 ").toProfile().description)
    }

    @Test
    fun bannerQueNaoServeDeSegmentoDeCaminhoEDescartado() {
        for (bad in listOf("", "a/b", "../x", "a?b", "a#b", "a%2Fb", "a b", "a.png")) {
            assertNull(dto(banner = bad).toProfile().bannerSha, bad)
        }
        assertEquals("Ab-_09", dto(banner = "Ab-_09").toProfile().bannerSha)
    }

    @Test
    fun dataDeCriacaoIlegivelOuAusenteNaoQuebra() {
        assertNull(dto(createdAt = null).toProfile().memberSince)
        assertNull(dto(createdAt = "ontem").toProfile().memberSince)
    }

    @Test
    fun nomeDeExibicaoUsaOApelidoOuOUsername() {
        assertEquals("Ana Lima", profile(nickname = "Ana Lima").displayName)
        assertEquals("ana", profile(nickname = null).displayName)
        assertEquals("ana", UserProfile("u1", "ana", "  ", null, null, null, null, emptyList()).displayName)
    }

    // profileHeader

    @Test
    fun cabecalhoPrefereOQueOAppSabeDoUsuario() {
        val header = profileHeader(user(nickname = "Ana ao vivo", statusMessage = "recado novo"), profile(nickname = "Ana velha", statusMessage = "recado velho"))!!

        assertEquals("Ana ao vivo", header.displayName)
        assertEquals("ana", header.username)
        assertEquals("recado novo", header.statusMessage)
    }

    @Test
    fun recadoLimpoAoVivoNaoVoltaDoPerfilGuardado() {
        val header = profileHeader(user(statusMessage = null), profile(statusMessage = "recado velho"))!!

        assertNull(header.statusMessage)
    }

    @Test
    fun recadoEmBrancoDoUsuarioConhecidoNaoApareceNoCabecalho() {
        assertNull(profileHeader(user(statusMessage = "   "), null)!!.statusMessage)
    }

    @Test
    fun semUsuarioConhecidoOCabecalhoVemDoPerfil() {
        val header = profileHeader(null, profile(nickname = "Ana Lima", statusMessage = "Em reunião"))!!

        assertEquals("Ana Lima", header.displayName)
        assertEquals("ana", header.username)
        assertEquals("Em reunião", header.statusMessage)
    }

    @Test
    fun semNadaConhecidoNaoHaCabecalho() {
        assertNull(profileHeader(null, null))
    }

    // Carimbo e nome do avatar

    @Test
    fun carimboGuardaPresencaVersaoDoAvatarEApelido() {
        val presence = Presence("online", "oi")

        assertEquals(ProfileStamp(presence, 3L, "Ana"), profileStamp(user(nickname = "Ana", avatarVersion = 3), presence))
        assertEquals(ProfileStamp(null, null, null), profileStamp(null, null))
    }

    @Test
    fun carimboMudaQuandoAlgoQueInvalidaOPerfilMuda() {
        val base = profileStamp(user(nickname = "Ana", avatarVersion = 3), Presence("online", null))

        assertEquals(base, profileStamp(user(nickname = "Ana", avatarVersion = 3), Presence("online", null)))
        assertNotEquals(base, profileStamp(user(nickname = "Ana", avatarVersion = 3), Presence("away", null)))
        assertNotEquals(base, profileStamp(user(nickname = "Ana", avatarVersion = 3), Presence("online", "recado")))
        assertNotEquals(base, profileStamp(user(nickname = "Ana", avatarVersion = 4), Presence("online", null)))
        assertNotEquals(base, profileStamp(user(nickname = "Ana Lima", avatarVersion = 3), Presence("online", null)))
    }

    @Test
    fun avatarDoPerfilUsaOMesmoNomeDoAvatarDasMensagens() {
        assertEquals("avatar/u1/7", avatarMediaName("u1", 7))
    }
}
