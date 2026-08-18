import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import me.felin.gradle.plugins.cloudstream.CloudstreamPlugin

plugins {
    alias(libs.plugins.cloudstream)
}

cloudstream {
    setLanguage("tr")
    setAuthors(listOf("Antigravity"))
    setStatus(CloudstreamPlugin.STATUS_WORKING)
    setTypes(listOf(CloudstreamPlugin.TYPE_MOVIE))

    version = 6
}
