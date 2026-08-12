package dev.krimsn.healthconnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Handles the "privacy policy" link on Health Connect's own permission-request
 * screen (androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE through Android 13,
 * the activity-alias in AndroidManifest.xml on Android 14+). This app has no
 * backend of its own and no third-party data sharing beyond the user's own
 * Supabase project, so the "policy" is a short factual statement rather than a
 * legal document.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Box(modifier = Modifier.padding(24.dp)) {
                        Text(
                            "Health Sync reads the data types you grant it from Health " +
                                "Connect and uploads them over HTTPS to your own Supabase " +
                                "project. Nothing is sent anywhere else; there are no " +
                                "analytics or third-party SDKs in this app.",
                        )
                    }
                }
            }
        }
    }
}
