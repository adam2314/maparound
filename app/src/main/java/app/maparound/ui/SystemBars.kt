package app.maparound.ui

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat

/**
 * The app drawn edge to edge, behind the status and navigation bars, without the window APIs
 * Android 15 deprecated (Google Play warns about them): Window.setStatusBarColor and
 * setNavigationBarColor, the short-edges cutout mode. androidx.activity's enableEdgeToEdge()
 * still calls those in every version, so it isn't used.
 *
 * From Android 15 the system draws every app edge to edge, with see-through bars and a scrim
 * behind three-button navigation. Before that the bar colours come from the theme
 * (values/themes.xml, the navigation_bar colours) and [setUp] lays the window out behind them.
 */
object SystemBars {
    /** In onCreate, before setContent. */
    fun setUp(activity: Activity) {
        val window = activity.window
        if (Build.VERSION.SDK_INT < 35) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            // Into the camera cutout on every side, as Android 15 does by itself. (Android 9 and 10
            // have no such mode: their landscape keeps a band beside the cutout.)
            if (Build.VERSION.SDK_INT >= 30) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
        }
        val dark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        icons(window, dark, navigation = true)
    }

    /**
     * Dark bar icons in the light theme, light ones in the dark theme. Called again when the
     * system switches between them (MainActivity handles uiMode itself, so it isn't recreated).
     * Before Android 10 the navigation bar's scrim is the theme's colour from when the activity
     * started, so its icons stay as [setUp] left them.
     */
    fun icons(window: Window, dark: Boolean, navigation: Boolean = Build.VERSION.SDK_INT >= 29) {
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.isAppearanceLightStatusBars = !dark
        if (navigation) bars.isAppearanceLightNavigationBars = !dark
    }
}
