package dev.kokpit.anaekran.ekran

/** The app's screens. [temel] screens are where the panels (climate, apps) return to. */
enum class Ekran(val temel: Boolean) {
    ACILIS(true),
    PARK(true),
    SURUS(true),
    KLIMA(false),
    UYGULAMALAR(false),
}
