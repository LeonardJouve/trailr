package ch.trailer.android

sealed class Screen(val route: String) {
    data object Trails : Screen("trails")
    data object Map : Screen("map")
}