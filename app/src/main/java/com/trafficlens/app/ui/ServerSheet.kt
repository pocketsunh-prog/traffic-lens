package com.trafficlens.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trafficlens.app.data.FeedMode
import com.trafficlens.app.ui.theme.Tint

/** Bottom sheet for choosing between the built-in simulation and the Flask stream. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSheet(
    initialMode: FeedMode,
    initialUrl: String,
    onDismiss: () -> Unit,
    onApply: (FeedMode, String) -> Unit
) {
    var mode by remember { mutableStateOf(initialMode) }
    var url by remember { mutableStateOf(initialUrl) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F1128),
        contentColor = Tint.TextPrimary,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Tint.CardBorderStart)
            )
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 20.dp)
        ) {
            Text("Feed source", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Run the companion Flask + YOLOv8 server, explore the night-traffic simulation, or detect offline with the model bundled in the app.",
                fontSize = 11.5.sp,
                color = Tint.TextSecondary,
                lineHeight = 16.sp
            )

            Spacer(Modifier.height(18.dp))
            ModeSelector(mode = mode, onChange = { mode = it })

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Server URL", fontSize = 11.sp) },
                placeholder = { Text("http://192.168.128.140:8080", fontSize = 12.sp) },
                singleLine = true,
                enabled = mode == FeedMode.SERVER,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Tint.Violet,
                    unfocusedBorderColor = Tint.CardBorderStart,
                    focusedLabelColor = Tint.Violet,
                    unfocusedLabelColor = Tint.TextTertiary,
                    disabledBorderColor = Tint.CardBorderStart.copy(alpha = 0.4f),
                    focusedTextColor = Tint.TextPrimary,
                    unfocusedTextColor = Tint.TextPrimary,
                    disabledTextColor = Tint.TextTertiary,
                    focusedContainerColor = Color.White.copy(alpha = 0.03f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.03f),
                    cursorColor = Tint.Violet
                )
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("192.168.128.140:8080", "10.0.2.2:8080").forEach { preset ->
                    Text(
                        text = preset,
                        color = Tint.TextSecondary,
                        fontSize = 9.5.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(7.dp))
                            .clickable { url = "http://$preset" }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "The app expects GET /video_feed (MJPEG) and GET /stats (JSON). The default LAN address is 192.168.128.140:8080; 10.0.2.2 reaches the host from the emulator. On-device mode uses the camera and needs no network.",
                fontSize = 9.5.sp,
                color = Tint.TextTertiary,
                lineHeight = 13.sp
            )

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onApply(mode, url) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Tint.Violet,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = when (mode) {
                        FeedMode.SERVER -> "Connect to server"
                        FeedMode.ON_DEVICE -> "Use on-device camera"
                        FeedMode.DEMO -> "Use simulation"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ModeSelector(mode: FeedMode, onChange: (FeedMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ModeOption(
            title = "Simulation",
            subtitle = "offline demo",
            selected = mode == FeedMode.DEMO,
            modifier = Modifier.weight(1f),
            onClick = { onChange(FeedMode.DEMO) }
        )
        ModeOption(
            title = "Live server",
            subtitle = "MJPEG stream",
            selected = mode == FeedMode.SERVER,
            modifier = Modifier.weight(1f),
            onClick = { onChange(FeedMode.SERVER) }
        )
        ModeOption(
            title = "On-device",
            subtitle = "camera + TFLite",
            selected = mode == FeedMode.ON_DEVICE,
            modifier = Modifier.weight(1f),
            onClick = { onChange(FeedMode.ON_DEVICE) }
        )
    }
}

@Composable
private fun ModeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) Brush.linearGradient(listOf(Tint.Violet.copy(alpha = 0.85f), Tint.Indigo.copy(alpha = 0.85f)))
                else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            color = if (selected) Color.White else Tint.TextSecondary,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Text(
            text = subtitle,
            color = if (selected) Color.White.copy(alpha = 0.75f) else Tint.TextTertiary,
            fontSize = 8.sp,
            maxLines = 1
        )
    }
}
