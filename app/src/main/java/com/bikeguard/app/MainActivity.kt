package com.bikeguard.app

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.bikeguard.app.ui.AppRoot
import com.bikeguard.app.ui.LockScreen
import com.bikeguard.app.ui.theme.BikeGuardTheme

class MainActivity : FragmentActivity() {
    private var locked by mutableStateOf(false)
    private var prompting = false
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        locked = bikeApp.prefs.appLock && bikeApp.prefs.isLoggedIn
        setContent {
            BikeGuardTheme {
                Box(Modifier.fillMaxSize()) {
                    AppRoot()
                    if (locked) LockScreen(onUnlock = { prompt() })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val p = bikeApp.prefs
        // With the lock on, hide the app from screenshots and the recent-apps preview.
        if (p.appLock) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val away = stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > 30_000
        if (p.appLock && p.isLoggedIn && (locked || away)) { locked = true; prompt() }
    }

    override fun onStop() {
        stoppedAt = SystemClock.elapsedRealtime()
        super.onStop()
    }

    /** Fingerprint / face, falling back to the phone's PIN, pattern or password. Tracking is unaffected by the lock. */
    private fun prompt() {
        if (prompting) return
        prompting = true
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.lock_title))
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false; locked = false
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { prompting = false }
        }).authenticate(info)
    }
}
