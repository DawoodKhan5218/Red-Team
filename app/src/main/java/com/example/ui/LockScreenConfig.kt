package com.example.ui

/**
 * LockScreenConfig.kt
 *
 * EDIT THIS FILE TO CUSTOMIZE THE LOCK SCREEN OVERLAY TEXT DIRECTLY IN KOTLIN CODE.
 *
 * Simply edit any of the string constants below. They will be displayed on the Lock Screen overlay.
 */
object LockScreenConfig {

    /**
     * Main header title on the lock screen overlay (e.g., "THIS PHONE IS LOST")
     */
    const val LOCK_SCREEN_TITLE: String = "THIS PHONE IS LOST"

    /**
     * Subtitle note displayed directly under the main header
     */
    const val LOCK_SCREEN_SUBTITLE: String = "Device is locked in lost mode"

    /**
     * Main custom message text displayed to whoever finds the device
     */
    const val LOCK_SCREEN_MESSAGE: String = "This phone is lost. If found, please return it to the owner. Contact the owner or transfer details to the account number below to claim your reward."

    /**
     * Reward banner note (e.g., "REWARD: Rs 6000")
     */
    const val LOCK_SCREEN_REWARD: String = "REWARD: Rs 6000"

    /**
     * Payment account or phone number displayed on the reward card
     */
    const val LOCK_SCREEN_ACCOUNT_NUMBER: String = "0300-1234567"

    /**
     * Owner contact phone number
     */
    const val OWNER_PHONE: String = ""

    /**
     * Owner contact email address
     */
    const val OWNER_EMAIL: String = ""
}
