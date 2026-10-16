package com.jarvis.assistant.ui

import androidx.compose.foundation.background
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
) {
    var input by remember { mutableStateOf("") }
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

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Jarvis'e yaz...") },
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

@Composable
private fun BubbleView(b: ChatBubble) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (b.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
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
