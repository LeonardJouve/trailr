package ch.trailer.android.domain

import android.content.Context
import ch.trailer.android.BuildConfig

object MapStyle {

    private const val API_URL_PLACEHOLDER = "{api_url}"

    fun render(template: String, apiUrl: String): String =
        template.replace(API_URL_PLACEHOLDER, apiUrl)

    fun load(context: Context): String = render(
        context.assets.open("swisstopo.json").bufferedReader().use { it.readText() },
        BuildConfig.API_URL,
    )
}
