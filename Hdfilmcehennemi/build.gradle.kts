// Use an integer for version numbers
version = 8

cloudstream {
    description = "HDFilmCehennemi Turkce Film ve Dizi Saglayicisi"
    authors = listOf("CloudStream User")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "tr"
    iconUrl = "https://www.hdfilmcehennemi.nl/favicon.ico"
}

android {
    defaultConfig {
        minSdk = 21
    }
}
