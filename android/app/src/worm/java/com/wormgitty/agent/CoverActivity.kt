package com.wormgitty.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** worm cover: English chat screen backed by the /cover/chat relay. */
class CoverActivity : ComponentActivity() {

    private data class Msg(val text: String, val mine: Boolean, val t: String)

    private val seed = listOf(
        Msg("hey, are we still on for tonight?", false, "18:42"),
        Msg("yeah! finishing up now", true, "18:43"),
        Msg("cool, same place as usual?", false, "18:43"),
        Msg("yep. bringing sam too", true, "18:44"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ChatScreen() } }
    }

    private fun now(): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            .format(java.util.Date())

    @Composable
    private fun ChatScreen() {
        val msgs = remember { mutableStateListOf<Msg>().also { it.addAll(seed) } }
        var input by remember { mutableStateOf("") }
        var typing by remember { mutableStateOf(false) }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        LaunchedEffect(msgs.size, typing) {
            if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
        }

        Column(Modifier.fillMaxSize().background(Color(0xFF111B21))) {
            // contact bar
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF1F2C34)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).background(Color(0xFF00A884), RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) { Text("AM", color = Color.White) }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Alex Morgan", color = Color(0xFFE9EDEF),
                        style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (typing) "typing…" else "online",
                        color = Color(0xFF8696A0),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(msgs) { m -> Bubble(m) }
                if (typing) {
                    item {
                        Box(
                            Modifier.background(Color(0xFF1F2C34), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) { Text("…", color = Color(0xFF8696A0)) }
                    }
                }
            }

            // input row
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF1F2C34)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f)
                        .background(Color(0xFF2A3942), RoundedCornerShape(22.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    textStyle = LocalTextStyle.current.copy(color = Color(0xFFE9EDEF)),
                    decorationBox = { inner ->
                        if (input.isEmpty()) Text("Message", color = Color(0xFF8696A0))
                        inner()
                    },
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    val t = input.trim()
                    if (t.isEmpty() || typing) return@TextButton
                    input = ""
                    msgs.add(Msg(t, true, now()))
                    typing = true
                    scope.launch {
                        val reply = withContext(Dispatchers.IO) {
                            CoverChat.send(this@CoverActivity, t)
                        }
                        typing = false
                        msgs.add(Msg(reply, false, now()))
                    }
                }) { Text("Send", color = Color(0xFF00A884)) }
            }
        }
    }

    @Composable
    private fun Bubble(m: Msg) {
        Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
            Column(
                Modifier
                    .widthIn(max = 280.dp)
                    .background(
                        if (m.mine) Color(0xFF005C4B) else Color(0xFF1F2C34),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(m.text, color = Color(0xFFE9EDEF), style = MaterialTheme.typography.bodyMedium)
                Text(
                    m.t,
                    color = Color(0xFF8696A0),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}
