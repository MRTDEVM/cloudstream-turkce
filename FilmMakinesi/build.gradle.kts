// Use an integer for version numbers
version = 6

cloudstream {
    description = "FilmMakinesi Turkce Film ve Dizi Saglayicisi"
    authors = listOf("CloudStream User")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "tr"
    iconUrl = "https://filmmakinesi.to/favicon.ico"
}

android {
    defaultConfig {
        minSdk = 21
    }
}
