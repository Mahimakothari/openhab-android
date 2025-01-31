/*
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.habdroid.ui

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.KeyguardManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.view.isInvisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import org.openhab.habdroid.R
import org.openhab.habdroid.util.Constants
import org.openhab.habdroid.util.ScreenLockMode
import org.openhab.habdroid.util.Util
import org.openhab.habdroid.util.getPrefs
import org.openhab.habdroid.util.getScreenLockMode
import kotlin.coroutines.CoroutineContext

abstract class AbstractBaseActivity : AppCompatActivity(), CoroutineScope {
    private val job = Job()
    override val coroutineContext: CoroutineContext get() = Dispatchers.Main + job
    protected open val forceNonFullscreen = false
    private var authPrompt: AuthPrompt? = null

    protected val isFullscreenEnabled: Boolean
        get() = getPrefs().getBoolean(Constants.PREFERENCE_FULLSCREEN, false)

    @CallSuper
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Util.getActivityThemeId(this))

        checkFullscreen()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val typedValue = TypedValue()
            theme.resolveAttribute(R.attr.colorPrimary, typedValue, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setTaskDescription(ActivityManager.TaskDescription(
                    getString(R.string.app_name),
                    R.mipmap.icon,
                    typedValue.data))
            } else {
                @Suppress("DEPRECATION")
                setTaskDescription(ActivityManager.TaskDescription(
                    getString(R.string.app_name),
                    BitmapFactory.decodeResource(resources, R.mipmap.icon),
                    typedValue.data))
            }
        }

        super.onCreate(savedInstanceState)
    }

    @CallSuper
    override fun onStart() {
        super.onStart()
        promptForDevicePasswordIfRequired()
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    override fun onResume() {
        super.onResume()
        checkFullscreen()
    }

    @JvmOverloads
    fun checkFullscreen(isEnabled: Boolean = isFullscreenEnabled) {
        var uiOptions = window.decorView.systemUiVisibility
        val flags = (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN)
        uiOptions = if (isEnabled && !forceNonFullscreen) {
            uiOptions or flags
        } else {
            uiOptions and flags.inv()
        }
        window.decorView.systemUiVisibility = uiOptions
    }

    @TargetApi(21)
    private fun promptForDevicePassword() {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val locked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) km.isDeviceSecure else km.isKeyguardSecure
        if (locked) {
            authPrompt = AuthPrompt()
            authPrompt?.authenticate()
        }
    }

    internal open fun doesLockModeRequirePrompt(mode: ScreenLockMode): Boolean {
        return mode != ScreenLockMode.Disabled
    }

    private fun promptForDevicePasswordIfRequired() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return
        }
        if (authPrompt != null) {
            return
        }
        if (doesLockModeRequirePrompt(getPrefs().getScreenLockMode(this))) {
            if (timestampNeedsReauth(lastAuthenticationTimestamp)) {
                promptForDevicePassword()
            }
        } else {
            // Reset last authentication timestamp when going from an activity requiring authentication to an
            // activity that does not require authentication, so that the prompt will re-appear when going back
            // to the activity requiring authentication
            lastAuthenticationTimestamp = 0L
        }
    }

    private fun timestampNeedsReauth(ts: Long) =
        ts == 0L || SystemClock.elapsedRealtime() - ts > AUTHENTICATION_VALIDITY_PERIOD

    private inner class AuthPrompt : BiometricPrompt.AuthenticationCallback() {
        private val contentView = findViewById<View>(R.id.activity_content)
        private val prompt = BiometricPrompt(this@AbstractBaseActivity, Dispatchers.Main.asExecutor(), this)

        fun authenticate() {
            val descriptionResId = if (getPrefs().getScreenLockMode(contentView.context) == ScreenLockMode.KioskMode) {
                R.string.screen_lock_unlock_preferences_description
            } else {
                R.string.screen_lock_unlock_screen_description
            }
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.app_name))
                .setDescription(getString(descriptionResId))
                .setDeviceCredentialAllowed(true)
                .build()
            contentView.isInvisible = true
            prompt.authenticate(info)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            super.onAuthenticationError(errorCode, errString)
            finish()
        }

        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            super.onAuthenticationSucceeded(result)
            lastAuthenticationTimestamp = SystemClock.elapsedRealtime()
            contentView.isInvisible = false
            authPrompt = null
        }
    }

    companion object {
        private const val AUTHENTICATION_VALIDITY_PERIOD = 2 * 60 * 1000L

        var lastAuthenticationTimestamp = 0L
    }
}
