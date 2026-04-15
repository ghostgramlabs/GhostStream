package com.ghoststream.core.model

object DeviceNameGenerator {
    private val adjectives = listOf(
        "Swift", "Silent", "Bright", "Bold", "Cool", "Quick", "Clever", "Happy",
        "Sharp", "Smart", "Brave", "Grand", "Golden", "Lucky", "Mighty", "Noble",
        "Perfect", "Ready", "Shiny", "Strong", "Vivid", "Warm", "Wise", "Zen"
    )

    private val animals = listOf(
        "Tiger", "Eagle", "Fox", "Panda", "Wolf", "Lion", "Bear", "Hawk",
        "Whale", "Shark", "Otter", "Raven", "Falcon", "Owl", "Cheetah", "Phoenix",
        "Dragon", "Pegasus", "Lynx", "Badger", "Ferret", "Meerkat", "Dolphin", "Stag"
    )

    fun generateName(ipAddress: String): String {
        // Use IP as seed for deterministic but random name
        val seed = ipAddress.hashCode().toLong()
        val adjIndex = (seed % adjectives.size).toInt().let { if (it < 0) it + adjectives.size else it }
        val animalIndex = ((seed / adjectives.size) % animals.size).toInt().let { if (it < 0) it + animals.size else it }

        return "${adjectives[adjIndex]} ${animals[animalIndex]}"
    }
}
