package chat.papo.mobile

import chat.papo.mobile.core.BuildInfo
import chat.papo.mobile.core.safeExternalUrl
import chat.papo.mobile.ui.about.AboutLink
import chat.papo.mobile.ui.about.VersionSummary
import chat.papo.mobile.ui.about.describeVersion
import chat.papo.mobile.ui.about.displayUrl
import chat.papo.mobile.ui.about.readableLicense
import chat.papo.mobile.ui.about.versionClipboardText
import kotlin.test.Test
import kotlin.test.assertEquals

class AboutInfoTest {
    @Test
    fun finalVersionHasNoBeta() {
        assertEquals(VersionSummary("0.9.0", null, 90000, false), describeVersion(BuildInfo("0.9.0", 90000)))
    }

    @Test
    fun betaVersionSplitsTheNumber() {
        assertEquals(VersionSummary("0.9.0", 2, 90002, false), describeVersion(BuildInfo("0.9.0-beta.2", 90002)))
    }

    @Test
    fun debugBuildIsFlagged() {
        assertEquals(true, describeVersion(BuildInfo("0.9.0", 90000, debuggable = true)).development)
    }

    @Test
    fun emptyBuildDoesNotBreak() {
        assertEquals(VersionSummary(null, null, null, false), describeVersion(BuildInfo()))
        assertEquals(VersionSummary(null, null, null, false), describeVersion(BuildInfo("  ", -1)))
    }

    @Test
    fun oddVersionNamesAppearWholeWithoutBeta() {
        assertEquals("0.9.0-beta.", describeVersion(BuildInfo("0.9.0-beta.", 1)).release)
        assertEquals("-beta.2", describeVersion(BuildInfo("-beta.2", 1)).release)
        assertEquals("1.0-rc1", describeVersion(BuildInfo("1.0-rc1", 1)).release)
        assertEquals(null, describeVersion(BuildInfo("1.0-rc1", 1)).beta)
        val overflow = describeVersion(BuildInfo("0.9.0-beta.99999999999", 1))
        assertEquals("0.9.0-beta.99999999999", overflow.release)
        assertEquals(null, overflow.beta)
    }

    @Test
    fun copiedTextHasNameVersionAndBuild() {
        assertEquals("Papo Mobile 0.9.0-beta.2 (90002)", versionClipboardText("Papo Mobile", BuildInfo("0.9.0-beta.2", 90002)))
    }

    @Test
    fun copiedTextSkipsWhatTheBuildLacks() {
        assertEquals("Papo Mobile 0.9.0", versionClipboardText("Papo Mobile", BuildInfo("0.9.0", 0)))
        assertEquals("Papo Mobile (90000)", versionClipboardText("Papo Mobile", BuildInfo(" ", 90000)))
        assertEquals("Papo Mobile", versionClipboardText("Papo Mobile", BuildInfo()))
    }

    @Test
    fun everyAboutLinkIsOpenable() {
        for (link in AboutLink.entries) assertEquals(link.url, safeExternalUrl(link.url), link.name)
    }

    @Test
    fun displayUrlDropsTheScheme() {
        assertEquals("github.com/prodesert22", displayUrl(AboutLink.Developer.url))
    }

    @Test
    fun readableLicenseJoinsHardWrappedParagraphs() {
        val raw = "                    TITLE\r\n                 Version 3\n\n  First line\nsecond line\n\n\n    indented\n    block\n"
        assertEquals("TITLE Version 3\n\nFirst line second line\n\nindented block", readableLicense(raw))
    }

    @Test
    fun readableLicenseOfEmptyTextIsEmpty() {
        assertEquals("", readableLicense(" \n\n"))
    }
}
