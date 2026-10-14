package com.example.besharp.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute {
    @Serializable
    data object Gallery : AppRoute

    @Serializable
    data class PhotoDetail(val photoId: Long) : AppRoute

    @Serializable
    data class AssessmentResult(val assessmentId: Long) : AppRoute

    @Serializable
    data object Analysis : AppRoute

    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object Legal : AppRoute
}
