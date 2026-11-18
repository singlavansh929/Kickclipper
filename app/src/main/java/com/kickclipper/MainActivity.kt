package com.kickclipper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }

    @Composable
    fun Screen() {
        var url by remember { mutableStateOf("") }
        var startMin by remember { mutableStateOf("0") }
        var scanMin by remember { mutableStateOf("20") }
        var count by remember { mutableStateOf("5") }
        var clipSec by remember { mutableStateOf("30") }
        var busy by remember { mutableStateOf(false) }
        val log = remember { mutableStateListOf<String>() }
        val scope = rememberCoroutineScope()

        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Kick Clipper", style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(
                url, { url = it }, Modifier.fillMaxWidth(),
                label = { Text("Kick or YouTube link (or .m3u8)") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Num("Start min", startMin, Modifier.weight(1f)) { startMin = it }
                Num("Scan min", scanMin, Modifier.weight(1f)) { scanMin = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Num("Clips", count, Modifier.weight(1f)) { count = it }
                Num("Clip sec", clipSec, Modifier.weight(1f)) { clipSec = it }
            }
            Button(
                enabled = !busy && url.isNotBlank(),
                onClick = {
                    busy = true; log.clear()
                    scope.launch {
                        try {
                            Clipper.run(
                                applicationContext, url,
                                startMin.toIntOrNull() ?: 0, scanMin.toIntOrNull() ?: 20,
                                count.toIntOrNull() ?: 5, clipSec.toIntOrNull() ?: 30
                            ) { log.add(it) }
                        } catch (e: Exception) {
                            log.add("Error: ${e.message}")
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Working..." else "Make clips") }
            log.forEach { Text(it) }
        }
    }

    @Composable
    fun Num(label: String, v: String, m: Modifier, set: (String) -> Unit) =
        OutlinedTextField(
            v, { set(it.filter(Char::isDigit)) }, m,
            label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
}
