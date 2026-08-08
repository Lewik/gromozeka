package com.gromozeka.mobile.worker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class HealthPermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = rationaleColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Sleep data", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "Gromozeka Worker reads completed sleep sessions only when you enable sleep events. " +
                                "It converts them into asleep and awake state events.",
                        )
                        Text(
                            "Events are encrypted on this device, sent to the configured Gromozeka Server over HTTPS, " +
                                "and removed from the local outbox only after the Server acknowledges them.",
                        )
                        Text(
                            "The Server keeps state history for its owner. You can revoke Health Connect access in " +
                                "Android settings and remove this device's enrollment in Gromozeka Worker.",
                        )
                    }
                }
            }
        }
    }
}

private val rationaleColors = darkColorScheme(
    primary = Color(0xFFEF9F3B),
    background = Color(0xFF101714),
    surface = Color(0xFF101714),
    onBackground = Color(0xFFF2F0E8),
    onSurface = Color(0xFFF2F0E8),
)
