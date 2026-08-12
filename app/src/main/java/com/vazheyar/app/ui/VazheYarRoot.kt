package com.vazheyar.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vazheyar.app.BuildConfig
import com.vazheyar.app.MainViewModel
import com.vazheyar.app.ai.AiProviderMode
import com.vazheyar.app.data.EnrichmentStatus
import com.vazheyar.app.data.FlashcardEntity
import com.vazheyar.app.review.FsrsScheduler
import com.vazheyar.app.review.ReviewRating
import kotlinx.coroutines.flow.StateFlow

private enum class Tab(val title: String) {
    HOME("Home"), REVIEW("Review"), ADD("Add"), LIBRARY("Words"), SETTINGS("Settings")
}

private enum class LibraryFilter(val label: String) {
    ALL("All"), DUE("Due"), READY("Ready"), PENDING("Pending"), FAILED("Failed"), LEARNED("Reviewed")
}

private enum class LibrarySort(val label: String) {
    NEWEST("Newest"), A_Z("A–Z"), DUE_FIRST("Due first"), MOST_REVIEWED("Most reviewed")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VazheYarRoot(vm: MainViewModel) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    val snackbars = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateCompat()

    LaunchedEffect(message) {
        message?.let {
            snackbars.showSnackbar(it)
            vm.clearMessage()
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("flashcard", fontWeight = FontWeight.Bold)
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbars) },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { item ->
                        val icon = when (item) {
                            Tab.HOME -> Icons.Default.Home
                            Tab.REVIEW -> Icons.Default.School
                            Tab.ADD -> Icons.Default.Add
                            Tab.LIBRARY -> Icons.Default.LibraryBooks
                            Tab.SETTINGS -> Icons.Default.Settings
                        }
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = {
                                tab = item
                                if (item == Tab.REVIEW) vm.refreshDue()
                            },
                            icon = { Icon(icon, contentDescription = item.title) },
                            label = { Text(item.title) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    Tab.HOME -> HomeScreen(vm) {
                        tab = Tab.REVIEW
                        vm.refreshDue()
                    }
                    Tab.REVIEW -> ReviewScreen(vm)
                    Tab.ADD -> AddScreen(vm)
                    Tab.LIBRARY -> LibraryScreen(vm)
                    Tab.SETTINGS -> SettingsScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(vm: MainViewModel, onReview: () -> Unit) {
    val total by vm.totalCount.collectAsStateCompat()
    val learned by vm.learnedCount.collectAsStateCompat()
    val pending by vm.pendingCount.collectAsStateCompat()
    val failed by vm.failedCount.collectAsStateCompat()
    val dueCount by vm.dueCount.collectAsStateCompat()
    val aiReady by vm.aiReady.collectAsStateCompat()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Today's review", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("FSRS-6 schedules each word using its difficulty, stability, and recall history.")

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Total", total.toString(), Modifier.weight(1f))
            StatCard("Reviewed", learned.toString(), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Due now", dueCount.toString(), Modifier.weight(1f))
            StatCard("AI queue", pending.toString(), Modifier.weight(1f))
        }
        if (failed > 0) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("$failed card(s) need attention", fontWeight = FontWeight.Bold)
                    Text("Open Words to inspect an error, or Settings to retry all failed cards.")
                }
            }
        }

        Button(onClick = onReview, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
            Icon(Icons.Default.School, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (dueCount == 0) "Check reviews" else "Start review ($dueCount)")
        }

        if (pending > 0) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (aiReady) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (aiReady) "AI is creating flashcards" else "AI provider required", fontWeight = FontWeight.Bold)
                    if (aiReady) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        if (aiReady) "Pending cards are processed in batches of up to 20 words."
                        else "Configure Gemini or Groq in Settings."
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label)
        }
    }
}

@Composable
private fun ReviewScreen(vm: MainViewModel) {
    val cards by vm.dueCards.collectAsStateCompat()
    val card = cards.firstOrNull()
    val speaker = rememberTtsSpeaker()

    if (card == null) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(16.dp))
            Text("Review complete", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("There are no cards due right now.", textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = vm::refreshDue) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Check again")
            }
        }
        return
    }

    val previews = remember(card.id, card.lastReviewedAt, card.fsrsState, card.fsrsStep, card.fsrsStability) {
        ReviewRating.entries.associateWith { FsrsScheduler.preview(card, it) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("1 of ${cards.size}", style = MaterialTheme.typography.labelLarge)
        Text("Tap the card to flip • FSRS-6")

        FlipFlashcard(card = card, onSpeak = { speaker.speak(card.word) })

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReviewButton("Again", previews[ReviewRating.AGAIN].orEmpty(), Modifier.weight(1f)) {
                vm.review(card, ReviewRating.AGAIN)
            }
            ReviewButton("Hard", previews[ReviewRating.HARD].orEmpty(), Modifier.weight(1f)) {
                vm.review(card, ReviewRating.HARD)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReviewButton("Good", previews[ReviewRating.GOOD].orEmpty(), Modifier.weight(1f)) {
                vm.review(card, ReviewRating.GOOD)
            }
            ReviewButton("Easy", previews[ReviewRating.EASY].orEmpty(), Modifier.weight(1f)) {
                vm.review(card, ReviewRating.EASY)
            }
        }
    }
}

@Composable
private fun ReviewButton(label: String, interval: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(vertical = 10.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontWeight = FontWeight.Bold)
            Text(interval, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun FlipFlashcard(card: FlashcardEntity, onSpeak: () -> Unit) {
    var flipped by remember(card.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().height(390.dp).clickable { flipped = !flipped },
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(28.dp)
    ) {
        AnimatedContent(targetState = flipped, label = "flip-card") { back ->
            if (back) CardBack(card) else CardFront(card, onSpeak)
        }
    }
}

@Composable
private fun CardFront(card: FlashcardEntity, onSpeak: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(card.word, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Text(card.ipa, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(18.dp))
        FilledTonalButton(onClick = onSpeak) {
            Icon(Icons.Default.VolumeUp, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Pronounce")
        }
        Spacer(Modifier.height(28.dp))
        Text("Tap to see meanings and example", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CardBack(card: FlashcardEntity) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Text(
                card.translationFa.ifBlank { "—" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(24.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(card.exampleEn.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(28.dp))
        Text("Tap again to return", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AddScreen(vm: MainViewModel) {
    var word by remember { mutableStateOf("") }
    val progress by vm.importProgress.collectAsStateCompat()
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(vm::importCsv)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("Add words", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Add one English word, or import a CSV file. Duplicate words are skipped automatically.")

        OutlinedTextField(
            value = word,
            onValueChange = { word = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("English word") },
            placeholder = { Text("e.g. remarkable") }
        )
        Button(
            onClick = { vm.addWord(word); word = "" },
            enabled = word.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add flashcard")
        }

        HorizontalDivider()

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("CSV import", fontWeight = FontWeight.Bold)
                Text("Required column: word. Optional columns: ipa, meaningsFa, exampleEn. If optional content is missing, AI completes the card.")
                OutlinedButton(
                    onClick = { csvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream")) },
                    enabled = !progress.active,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose CSV file")
                }
            }
        }

        if (progress.active || progress.total > 0) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (progress.active) "Importing…" else "Last import", fontWeight = FontWeight.Bold)
                    if (progress.active) {
                        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                    }
                    Text("Processed ${progress.processed} / ${progress.total}")
                    Text("Added: ${progress.added} • Duplicates skipped: ${progress.duplicates}")
                    if (progress.readyFromCsv > 0) Text("Complete cards from CSV: ${progress.readyFromCsv}")
                    if (progress.aiTotal > 0) {
                        val aiFraction = (progress.aiCompleted.toFloat() / progress.aiTotal).coerceIn(0f, 1f)
                        Text("AI enrichment: ${progress.aiCompleted} / ${progress.aiTotal}")
                        LinearProgressIndicator(progress = { aiFraction }, modifier = Modifier.fillMaxWidth())
                        if (progress.aiGeminiCompleted > 0 || progress.aiGroqCompleted > 0) {
                            Text("Gemini: ${progress.aiGeminiCompleted} • Groq: ${progress.aiGroqCompleted}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (progress.aiFailed > 0) {
                            Text("AI failures: ${progress.aiFailed}", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(vm: MainViewModel) {
    val cards by vm.allCards.collectAsStateCompat()
    val dueTick by vm.dueCount.collectAsStateCompat()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var sort by remember { mutableStateOf(LibrarySort.NEWEST) }
    var pendingDelete by remember { mutableStateOf<FlashcardEntity?>(null) }
    var editing by remember { mutableStateOf<FlashcardEntity?>(null) }
    val now = System.currentTimeMillis()

    val visible = remember(cards, query, filter, sort, dueTick) {
        val q = query.trim().lowercase()
        val filtered = cards.filter { card ->
            val matchesSearch = q.isBlank() || listOf(card.word, card.ipa, card.translationFa, card.exampleEn)
                .any { it.lowercase().contains(q) }
            val matchesFilter = when (filter) {
                LibraryFilter.ALL -> true
                LibraryFilter.DUE -> card.enrichmentStatus == EnrichmentStatus.READY.name && card.nextReviewAt <= now
                LibraryFilter.READY -> card.enrichmentStatus == EnrichmentStatus.READY.name
                LibraryFilter.PENDING -> card.enrichmentStatus == EnrichmentStatus.PENDING.name
                LibraryFilter.FAILED -> card.enrichmentStatus == EnrichmentStatus.FAILED.name
                LibraryFilter.LEARNED -> card.reviewCount > 0
            }
            matchesSearch && matchesFilter
        }
        when (sort) {
            LibrarySort.NEWEST -> filtered.sortedByDescending { it.createdAt }
            LibrarySort.A_Z -> filtered.sortedBy { it.word.lowercase() }
            LibrarySort.DUE_FIRST -> filtered.sortedBy { it.nextReviewAt }
            LibrarySort.MOST_REVIEWED -> filtered.sortedWith(compareByDescending<FlashcardEntity> { it.reviewCount }.thenBy { it.word.lowercase() })
        }
    }

    pendingDelete?.let { card ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete flashcard?") },
            text = { Text("Delete \"${card.word}\" and its review history?") },
            confirmButton = { Button(onClick = { vm.delete(card); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }

    editing?.let { card ->
        EditCardDialog(
            card = card,
            onDismiss = { editing = null },
            onSave = { word, ipa, meanings, example ->
                vm.editCard(card, word, ipa, meanings, example)
                editing = null
            }
        )
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Word library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                label = { Text("Search words, meanings, or examples") }
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryFilter.entries.forEach { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { filter = item },
                        label = { Text(item.label) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibrarySort.entries.forEach { item ->
                    FilterChip(
                        selected = sort == item,
                        onClick = { sort = item },
                        label = { Text(item.label) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("${visible.size} result(s)", style = MaterialTheme.typography.labelMedium)
        }

        items(visible, key = { it.id }) { card ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(card.word, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (card.ipa.isNotBlank()) Text(card.ipa, style = MaterialTheme.typography.bodySmall)
                        when (card.enrichmentStatus) {
                            EnrichmentStatus.READY.name -> {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                    Text(card.translationFa, maxLines = 2)
                                }
                                val source = listOfNotNull(card.enrichmentProvider, card.enrichmentModel).joinToString(" • ")
                                if (source.isNotBlank()) Text(source, style = MaterialTheme.typography.labelSmall)
                            }
                            EnrichmentStatus.PENDING.name -> Text("AI processing pending…", color = MaterialTheme.colorScheme.primary)
                            else -> Text(card.enrichmentError ?: "AI enrichment failed", color = MaterialTheme.colorScheme.error, maxLines = 3)
                        }
                    }
                    IconButton(onClick = { editing = card }) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                    if (card.enrichmentStatus == EnrichmentStatus.FAILED.name) {
                        IconButton(onClick = { vm.retryFailed(card) }) { Icon(Icons.Default.Refresh, contentDescription = "Retry") }
                    }
                    IconButton(onClick = { pendingDelete = card }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                }
            }
        }
    }
}

@Composable
private fun EditCardDialog(
    card: FlashcardEntity,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit
) {
    var word by remember(card.id) { mutableStateOf(card.word) }
    var ipa by remember(card.id) { mutableStateOf(card.ipa) }
    var meanings by remember(card.id) { mutableStateOf(card.translationFa) }
    var example by remember(card.id) { mutableStateOf(card.exampleEn) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit flashcard") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(word, { word = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Word") }, singleLine = true)
                OutlinedTextField(ipa, { ipa = it }, modifier = Modifier.fillMaxWidth(), label = { Text("IPA") }, singleLine = true)
                OutlinedTextField(meanings, { meanings = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Persian meanings") }, minLines = 3)
                OutlinedTextField(example, { example = it }, modifier = Modifier.fillMaxWidth(), label = { Text("English example") }, minLines = 2)
            }
        },
        confirmButton = { Button(onClick = { onSave(word, ipa, meanings, example) }, enabled = word.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val geminiConfigured by vm.geminiApiKeyConfigured.collectAsStateCompat()
    val groqConfigured by vm.groqApiKeyConfigured.collectAsStateCompat()
    val mode by vm.providerMode.collectAsStateCompat()
    val failed by vm.failedCount.collectAsStateCompat()

    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::exportBackup)
    }
    val csvExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(vm::exportCsv)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        restoreUri = uri
    }

    restoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("Restore backup?") },
            text = { Text("Restoring replaces the current library and review history. API keys are not changed.") },
            confirmButton = { Button(onClick = { vm.restoreBackup(uri); restoreUri = null }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Cancel") } }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset review progress?") },
            text = { Text("All ready cards will return to the beginning of FSRS learning. Words and AI content are kept, but review history is deleted.") },
            confirmButton = { Button(onClick = { vm.resetReviewProgress(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all cards?") },
            text = { Text("This permanently deletes all flashcards and review history. Export a backup first if you may need them later.") },
            confirmButton = { Button(onClick = { vm.deleteAllCards(); confirmDeleteAll = false }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } }
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Text("AI providers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Card {
            Column(Modifier.padding(14.dp)) {
                ProviderRadio("Auto", "Gemini first; Groq fallback", mode == AiProviderMode.AUTO) { vm.setAiProviderMode(AiProviderMode.AUTO) }
                ProviderRadio("Gemini only", "Gemini 3.6 Flash", mode == AiProviderMode.GEMINI) { vm.setAiProviderMode(AiProviderMode.GEMINI) }
                ProviderRadio("Groq only", "GPT-OSS 20B on GroqCloud", mode == AiProviderMode.GROQ) { vm.setAiProviderMode(AiProviderMode.GROQ) }
            }
        }

        ApiKeyCard(
            title = "Google Gemini",
            configured = geminiConfigured,
            placeholder = "Gemini API key",
            onSave = vm::saveGeminiApiKey,
            onClear = vm::clearGeminiApiKey
        )
        ApiKeyCard(
            title = "GroqCloud",
            configured = groqConfigured,
            placeholder = "Groq API key",
            onSave = vm::saveGroqApiKey,
            onClear = vm::clearGroqApiKey
        )

        HorizontalDivider()
        Text("Backup & export", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Backup JSON includes cards, FSRS state, and review history. API keys are never exported.")
        OutlinedButton(onClick = { backupLauncher.launch("flashcard-backup-${System.currentTimeMillis()}.json") }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Backup, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Create full backup")
        }
        OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Restore, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Restore backup")
        }
        OutlinedButton(onClick = { csvExportLauncher.launch("flashcard-export.csv") }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Export cards as CSV")
        }

        HorizontalDivider()
        Text("Maintenance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = vm::retryAllFailed, enabled = failed > 0, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (failed > 0) "Retry all failed cards ($failed)" else "No failed cards")
        }
        OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Reset all review progress")
        }
        OutlinedButton(onClick = { confirmDeleteAll = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Delete, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Delete all cards")
        }

        HorizontalDivider()
        Text("Security", fontWeight = FontWeight.Bold)
        Text("Gemini and Groq keys are encrypted with Android Keystore and remain on this device. They are not stored in the APK, repository, CSV export, or backup file.")

        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.labelLarge)
        Text("Database v2 • FSRS-6 • Gemini + Groq", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ProviderRadio(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ApiKeyCard(
    title: String,
    configured: Boolean,
    placeholder: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit
) {
    var key by remember { mutableStateOf("") }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (configured) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(if (configured) "API key configured" else "API key not configured", style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(if (configured) "Replace API key" else placeholder) }
            )
            Button(
                onClick = { onSave(key); key = "" },
                enabled = key.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (configured) "Replace key" else "Save key") }
            if (configured) {
                TextButton(onClick = onClear, modifier = Modifier.align(Alignment.End)) { Text("Remove key") }
            }
        }
    }
}

@Composable
private fun <T> StateFlow<T>.collectAsStateCompat() = this.collectAsState()
