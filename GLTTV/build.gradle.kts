plugins {
    // Make sure the Cloudstream extension plugin is applied here
    id("com.lagradost.cloudstream3.gradle") // Use the template's current version
}

version = 2

cloudstream {
    authors     = listOf("Digital")
    language    = "en"
    description = "GLT Tv"
    status      = 1
    tvTypes     = listOf("All")
    iconUrl     = "https://pinaycum.tv/favicon.ico"
}