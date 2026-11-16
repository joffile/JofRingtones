package com.jof.ringtones.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jof.ringtones.R
import com.jof.ringtones.data.Ringtone
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RingtoneScreen(viewModel: RingtonesViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val player by viewModel.player.state.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var sheetItem by remember { mutableStateOf<Ringtone?>(null) }
    var searchActive by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.onPermissionResult() }

    val writeSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.onScreenResume() }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> if (result.resultCode == Activity.RESULT_OK) viewModel.onDeleteConfirmed() }

    val contactPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri -> if (uri != null) viewModel.onContactPicked(uri) else viewModel.onContactPickCancelled() }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onContactsPermission(granted) }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.import(it) } }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.Message -> scope.launch { snackbarHostState.showSnackbar(event.text) }
                UiEvent.RequestWriteSettings -> {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    )
                    runCatching { writeSettingsLauncher.launch(intent) }.onFailure {
                        snackbarHostState.showSnackbar(context.getString(R.string.write_settings_unavailable))
                    }
                }
                UiEvent.RequestContactsPermission ->
                    contactsPermissionLauncher.launch(Manifest.permission.WRITE_CONTACTS)
                UiEvent.PickContact -> runCatching { contactPicker.launch(null) }
                is UiEvent.ConfirmDelete -> runCatching {
                    deleteLauncher.launch(IntentSenderRequest.Builder(event.intent).build())
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onScreenResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (searchActive) {
                        OutlinedTextField(
                            value = ui.query,
                            onValueChange = viewModel::setQuery,
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(stringResource(R.string.app_name))
                    }
                },
                navigationIcon = {
                    if (searchActive) {
                        IconButton(onClick = {
                            searchActive = false
                            viewModel.setQuery("")
                            focusManager.clearFocus()
                        }) { Icon(Icons.Rounded.ArrowBack, stringResource(R.string.close_search)) }
                    }
                },
                actions = {
                    if (!searchActive) {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Rounded.Search, stringResource(R.string.search))
                        }
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh))
                    }
                }
            )
        },
        bottomBar = {
            if (player.id != null) {
                PlayerBar(
                    state = player,
                    onToggle = {
                        ui.items.firstOrNull { it.id == player.id }?.let { viewModel.play(it) }
                            ?: viewModel.player.let { p -> if (player.isPlaying) p.pause() else p.resume() }
                    },
                    onSeek = viewModel.player::seekTo,
                    onClose = viewModel.player::stop
                )
            }
        },
        floatingActionButton = {
            if (ui.hasPermission) {
                ExtendedFloatingActionButton(
                    onClick = { runCatching { openDocumentLauncher.launch(arrayOf("audio/*")) } },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text(stringResource(R.string.import_audio)) }
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                !ui.hasPermission -> InfoPane(
                    icon = Icons.Rounded.FolderOpen,
                    title = stringResource(R.string.permission_title),
                    message = stringResource(R.string.permission_message),
                    actionLabel = stringResource(R.string.permission_grant),
                    onAction = { permissionLauncher.launch(Permissions.readPermissions()) },
                    secondaryLabel = stringResource(R.string.open_settings),
                    onSecondary = { openAppSettings(context) }
                )

                ui.loading && ui.totalCount == 0 -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                ui.totalCount == 0 -> InfoPane(
                    icon = Icons.Rounded.MusicOff,
                    title = stringResource(R.string.empty_title),
                    message = stringResource(R.string.empty_message),
                    actionLabel = stringResource(R.string.import_audio),
                    onAction = { runCatching { openDocumentLauncher.launch(arrayOf("audio/*")) } }
                )

                else -> {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !ui.favoritesOnly,
                            onClick = { viewModel.setFavoritesOnly(false) },
                            label = { Text(stringResource(R.string.filter_all)) }
                        )
                        FilterChip(
                            selected = ui.favoritesOnly,
                            onClick = { viewModel.setFavoritesOnly(true) },
                            label = { Text(stringResource(R.string.filter_favorites)) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Favorite, null, Modifier.size(18.dp))
                            }
                        )
                    }
                    if (ui.items.isEmpty()) {
                        InfoPane(
                            icon = Icons.Rounded.SearchOff,
                            title = stringResource(R.string.nothing_found),
                            message = stringResource(R.string.nothing_found_message)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)
                        ) {
                            items(ui.items, key = { it.id }) { item ->
                                RingtoneRow(
                                    item = item,
                                    isCurrent = item.id == ui.defaultRingtoneId,
                                    isFavorite = item.id in ui.favorites,
                                    isActive = player.id == item.id,
                                    isPlaying = player.id == item.id && player.isPlaying,
                                    isPreparing = player.id == item.id && player.isPreparing,
                                    onClick = { viewModel.play(item) },
                                    onFavorite = { viewModel.toggleFavorite(item.id) },
                                    onMore = { sheetItem = item }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    sheetItem?.let { item ->
        ActionsSheet(
            item = item,
            onDismiss = { sheetItem = null },
            onApply = { kind -> viewModel.applyAs(item, kind) },
            onContact = { viewModel.assignToContact(item) },
            onShare = { shareRingtone(context, item) },
            onDelete = { viewModel.delete(item) }
        )
    }
}

private fun shareRingtone(context: Context, item: Ringtone) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/*"
        putExtra(Intent.EXTRA_STREAM, item.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
    }.onFailure { /* нет приложений для отправки */ }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}
