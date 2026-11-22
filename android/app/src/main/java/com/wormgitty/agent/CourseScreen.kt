package com.wormgitty.agent

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection

/** One course topic for the ghost/master cover surfaces. */
data class CoverTopic(val id: String, val title: String, val subtitle: String, val body: String)

/**
 * Shared Arabic course cover: list of 5 topics with per-topic progress,
 * tapping opens the article body and marks progress in SharedPreferences.
 */
@Composable
fun CourseCoverScreen(context: Context, appTitle: String, topics: List<CoverTopic>) {
    val prefs = remember { context.getSharedPreferences("cover_progress", Context.MODE_PRIVATE) }
    var selected by remember { mutableStateOf<CoverTopic?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val read: (CoverTopic) -> Boolean = { prefs.getBoolean("read_${it.id}", false) || tick < 0 }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // header
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(16.dp)) {
                Text(appTitle, color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                val done = topics.count(read)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { done / topics.size.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text("$done / ${topics.size} دروس مكتملة", color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodySmall)
            }

            if (selected == null) {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(topics) { t ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selected = t
                                if (!read(t)) { prefs.edit().putBoolean("read_${t.id}", true).apply(); tick++ }
                            },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.title, style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(2.dp))
                                    Text(t.subtitle, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(if (read(t)) "✓" else "›",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            } else {
                val t = selected!!
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text(t.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text(t.body, style = MaterialTheme.typography.bodyLarge, lineHeight = MaterialTheme.typography.bodyLarge.lineHeight)
                }
                TextButton(onClick = { selected = null }, modifier = Modifier.padding(16.dp)) {
                    Text("‹ رجوع للقائمة")
                }
            }
        }
    }
}
