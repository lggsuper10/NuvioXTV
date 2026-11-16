package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.navigation.Screen

enum class StartupDestination {
    Loading,
    ProfileSelection,
    Setup,
    Home,
    Content
}

fun startupDestinationForRoute(route: String?): StartupDestination = when (route) {
    null -> StartupDestination.Loading
    Screen.ExperienceModeSelection.route, Screen.LayoutSelection.route -> StartupDestination.Setup
    Screen.Home.route -> StartupDestination.Home
    else -> StartupDestination.Content
}

fun shouldShowStartupSplash(
    enabled: Boolean,
    complete: Boolean,
    destination: StartupDestination
): Boolean = enabled && !complete &&
    (destination == StartupDestination.Loading || destination == StartupDestination.Home)

fun shouldShowHomeStartupLoader(
    loading: Boolean,
    sharedSplashEnabled: Boolean,
    startupComplete: Boolean
): Boolean = loading && (!sharedSplashEnabled || startupComplete)

fun shouldReleaseHomeStartupGate(
    layoutPreferencesReady: Boolean,
    isModernLayout: Boolean,
    modernPresentationReady: Boolean,
    hasHomeRows: Boolean,
    hasAnyContent: Boolean,
    hasError: Boolean
): Boolean {
    if (!layoutPreferencesReady) return false
    if (hasError) return true
    return if (isModernLayout) {
        modernPresentationReady
    } else {
        hasHomeRows || hasAnyContent
    }
}
