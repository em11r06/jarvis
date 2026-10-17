package com.jarvis.assistant.ui

import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.core.AssistantState
import com.jarvis.assistant.core.ChatBubble
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisSurface
import com.jarvis.assistant.ui.theme.JarvisSurfaceHigh

/** Ana ekran: üstte başlık, ortada reaktör, altında sohbet ve yazı kutusu. */
@Composable
fun ChatScreen(
    bubbles: List<ChatBubble>,
    state: AssistantState,
    partial: String,
    onSend: (String) -> Unit,
    onMic: () -> Unit,
    onSettings: () -> Unit,
    pendingImages: List<String> = emptyList(),
    onAttach: (List<android.net.Uri>) -> Unit = {},
    onRemoveImage: (String) -> Unit = {},
) {
    var input by remember { mutableStateOf("") }
    val pickImages = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(4),
    ) { uris -> if (uris.isNotEmpty()) onAttach(uris) }
    val listState = rememberLazyListState()
    LaunchedEffect(bubbles.size) {
        if (bubbles.isNotEmpty()) listState.animateScrollToItem(bubbles.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "J.A.R.V.I.S.",
                color = JarvisCyan,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Ayarlar", tint = JarvisCyan)
            }
        }

        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ArcReactor(state = state, onClick = onMic)
                Text(
                    when (state) {
                        AssistantState.IDLE -> "Konuşmak için dokunun"
                        AssistantState.LISTENING -> "Dinliyorum..."
                        AssistantState.THINKING -> "Düşünüyorum..."
                        AssistantState.SPEAKING -> "Konuşuyorum..."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (partial.isNotBlank()) {
                    Text(
                        partial,
                        color = JarvisCyan,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(bubbles) { BubbleView(it) }
        }

        if (pendingImages.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pendingImages.forEach { path ->
                    Box {
                        Thumb(path, 64.dp)
                        Icon(
                            Icons.Default.Close, contentDescription = "Kaldır", tint = JarvisCyan,
                            modifier = Modifier.align(Alignment.TopEnd).size(20.dp)
                                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(10.dp))
                                .clickable { onRemoveImage(path) },
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                pickImages.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Görsel ekle", tint = JarvisCyan)
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (pendingImages.isEmpty()) "Jarvis'e yaz..." else "Görsel hakkında sor...") },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
            )
            IconButton(onClick = {
                onSend(input)
                input = ""
            }) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Gönder", tint = JarvisCyan)
            }
        }
    }
}

/** Görselin küçük önizlemesi (dosya silinmişse boş kutu). */
@Composable
private fun Thumb(path: String, size: androidx.compose.ui.unit.Dp) {
    val bmp = remember(path) { com.jarvis.assistant.ai.Images.thumbnail(path)?.asImageBitmap() }
    val shape = RoundedCornerShape(10.dp)
    if (bmp != null) {
        Image(
            bmp, contentDescription = "Görsel",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(shape).border(1.dp, JarvisCyan.copy(alpha = 0.6f), shape),
        )
    } else {
        Box(Modifier.size(size).background(JarvisSurface, shape))
    }
}

@Composable
private fun BubbleView(b: ChatBubble) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (b.fromUser) Alignment.End else Alignment.Start) {
        if (b.images.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                b.images.forEach { Thumb(it, 120.dp) }
            }
        }
        val shape = RoundedCornerShape(16.dp)
        Text(
            b.text,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(if (b.fromUser) JarvisSurfaceHigh else JarvisSurface, shape)
                .border(1.dp, if (b.fromUser) JarvisCyan.copy(alpha = 0.3f) else JarvisCyan.copy(alpha = 0.7f), shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}
