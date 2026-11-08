package com.example.myapplication.ui.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.SectionRepository
import com.example.myapplication.data.db.SectionWithCount
import com.example.myapplication.ui.common.SectionNameDialog
import com.example.myapplication.ui.common.pluralItems
import com.example.myapplication.ui.common.sectionIcon
import com.example.myapplication.ui.common.sectionNameError
import com.example.myapplication.domain.SectionNameError

/** Главный экран: список видимых разделов (FR-SEC1), вход в покупки. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionsScreen(
    viewModel: SectionsViewModel,
    onOpenSection: (Long) -> Unit,
    onOpenShopping: () -> Unit,
    onOpenConnection: () -> Unit,
    onOpenAdvice: () -> Unit,
    syncViewModel: com.example.myapplication.ui.sync.SyncViewModel,
) {
    val sections by viewModel.sections.collectAsState()
    val syncState by syncViewModel.state.collectAsState()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(syncState.message) {
        syncState.message?.let {
            snackbar.showSnackbar(it)
            syncViewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Разделы") },
                actions = {
                    IconButton(onClick = { syncViewModel.sync() }, enabled = !syncState.running) {
                        Icon(Icons.Filled.Sync, contentDescription = "Синхронизировать")
                    }
                    IconButton(onClick = onOpenConnection) {
                        Icon(Icons.Filled.Settings, contentDescription = "Подключение")
                    }
                    IconButton(onClick = onOpenAdvice) {
                        Icon(Icons.Filled.Info, contentDescription = "Справка")
                    }
                    IconButton(onClick = onOpenShopping) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Список покупок")
                    }
                },
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { creating = true },
                modifier = Modifier.semantics { contentDescription = "Создать раздел" },
            ) { Icon(Icons.Filled.Add, contentDescription = null) }
        },
    ) { padding ->
        if (sections.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { Text("Разделов пока нет") }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sections, key = { it.section.uuid }) { item ->
                    SectionCard(item, onClick = { onOpenSection(item.section.id) })
                }
            }
        }
    }

    if (creating) {
        SectionNameDialog(
            title = "Новый раздел",
            initial = "",
            onConfirm = { name, onResult ->
                viewModel.createSection(name) { result -> onResult(result) }
            },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun SectionCard(item: SectionWithCount, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = sectionIcon(item.section.iconKey),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.section.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = pluralItems(item.productCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
