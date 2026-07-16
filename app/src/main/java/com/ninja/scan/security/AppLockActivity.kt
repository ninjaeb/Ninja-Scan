package com.ninja.scan.security

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.ninja.scan.R
import com.ninja.scan.ui.theme.DocScannerTheme
import com.ninja.scan.ui.theme.ThemePrefs

/**
 * Full-screen biometric gate shown on top of whatever's on screen whenever
 * the app returns to the foreground with [AppLock] enabled. Success unlocks
 * for the rest of this process's foreground session and finishes, revealing
 * the real screen underneath; back just backgrounds the whole app instead
 * of revealing it unauthenticated.
 */
class AppLockActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }

        setContent {
            val isDarkTheme = remember { ThemePrefs.isDark(this) }
            var errorText by remember { mutableStateOf<String?>(null) }

            val promptInfo = remember {
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(R.string.app_lock_prompt_title))
                    .setSubtitle(getString(R.string.app_lock_prompt_subtitle))
                    .setNegativeButtonText(getString(R.string.cancel))
                    .setAllowedAuthenticators(BIOMETRIC_STRONG)
                    .build()
            }
            val prompt = remember {
                BiometricPrompt(
                    this,
                    ContextCompat.getMainExecutor(this),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            AppLock.unlockedThisSession = true
                            finish()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            errorText = errString.toString()
                        }

                        override fun onAuthenticationFailed() {
                            errorText = getString(R.string.app_lock_not_recognized)
                        }
                    },
                )
            }

            LaunchedEffect(Unit) { prompt.authenticate(promptInfo) }

            DocScannerTheme(darkTheme = isDarkTheme) {
                AppLockScreen(
                    errorText = errorText,
                    onRetry = { errorText = null; prompt.authenticate(promptInfo) },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLock.lockActivityShowing = false
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, AppLockActivity::class.java)
    }
}

@Composable
private fun AppLockScreen(errorText: String?, onRetry: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(colorResource(R.color.ic_launcher_background)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(108.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.app_lock_prompt_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.app_lock_prompt_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            errorText?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.unlock)) }
        }
    }
}
