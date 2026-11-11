package app.maparound.ui

import android.content.Context
import app.maparound.places.Place
import app.maparound.share.Sharing

/** The shared screens' [PlatformActions] on Android (share sheet through [Sharing]). */
class AndroidPlatform(private val context: Context) : PlatformActions {
    override fun sharePointText(lat: Double, lon: Double, label: String?) = Sharing.sharePointText(context, lat, lon, label)
    override fun sharePointGpx(lat: Double, lon: Double, label: String?) = Sharing.sharePointGpx(context, lat, lon, label)
    override fun sharePlaces(places: List<Place>, title: String) = Sharing.sharePlaces(context, places, title)
}
