package com.example.besharp.domain.resolution

data class ResolutionPolicy(
    val version: Int = CURRENT_VERSION,
    val possibleSyntheticThreshold: Int = 70
) {
    init {
        require(version > 0)
        require(possibleSyntheticThreshold in 0..100)
    }

    companion object {
        const val CURRENT_VERSION = 2
    }
}
