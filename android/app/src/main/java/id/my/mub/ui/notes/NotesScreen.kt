package id.my.mub.ui.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.my.mub.data.NoteItem
import id.my.mub.data.NotesRepository
import id.my.mub.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotesScreen(
    onOpenRelay: () -> Unit
) {
    val context = LocalContext.current
    var notes by remember { mutableStateOf(NotesRepository.getNotes(context)) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var showMenu by remember { mutableStateOf(false) }

    // Note creation / editing dialog state
    var activeNoteToEdit by remember { mutableStateOf<NoteItem?>(null) }
    var showEditorDialog by remember { mutableStateOf(false) }
    var editTitle by remember { mutableStateOf("") }
    var editContent by remember { mutableStateOf("") }
    var editCategory by remember { mutableStateOf("Personal") }

    val categories = listOf("All", "Personal", "Work", "Reading", "Ideas")

    val filteredNotes = notes.filter { note ->
        (selectedCategory == "All" || note.category.equals(selectedCategory, ignoreCase = true)) &&
                (searchQuery.isBlank() ||
                        note.title.contains(searchQuery, ignoreCase = true) ||
                        note.content.contains(searchQuery, ignoreCase = true))
    }

    Scaffold(
        containerColor = BgMain,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    activeNoteToEdit = null
                    editTitle = ""
                    editContent = ""
                    editCategory = "Personal"
                    showEditorDialog = true
                },
                containerColor = AccentPrimary,
                contentColor = PureWhite,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Note")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // --- Header Bar with Secret Long-Press Gateway ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The title bar is the primary discreet unlock mechanism (Long-press to open Relay)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = { /* normal tap does nothing suspicious */ },
                            onLongClick = {
                                // Discreet gateway to Relay Dashboard
                                onOpenRelay()
                            }
                        )
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Column {
                        Text(
                            text = "QuickNotes",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${notes.size} notes saved locally",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = TextSecondary)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(SurfaceCardElevated)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Cloud Sync & Backup", color = TextPrimary) },
                            onClick = {
                                showMenu = false
                                onOpenRelay()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Sort by Date", color = TextSecondary) },
                            onClick = {
                                showMenu = false
                                notes = notes.sortedByDescending { it.timestamp }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("About QuickNotes (v2.1)", color = TextSecondary) },
                            onClick = { showMenu = false }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- Search Field ---
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search your notes...", color = TextMuted, fontSize = 13.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentPrimary,
                    unfocusedBorderColor = SurfaceCardBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // --- Category Filter Chips ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { cat ->
                    val isSelected = selectedCategory == cat
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) AccentPrimary else SurfaceCard)
                            .border(1.dp, if (isSelected) AccentPrimary else SurfaceCardBorder, RoundedCornerShape(20.dp))
                            .clickable { selectedCategory = cat }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = cat,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) PureWhite else TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // --- Notes List ---
            if (filteredNotes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isBlank()) "No notes yet. Tap '+' to create one." else "No notes matching \"$searchQuery\"",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredNotes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onClick = {
                                activeNoteToEdit = note
                                editTitle = note.title
                                editContent = note.content
                                editCategory = note.category
                                showEditorDialog = true
                            },
                            onDelete = {
                                NotesRepository.deleteNote(context, note.id)
                                notes = NotesRepository.getNotes(context)
                            }
                        )
                    }
                }
            }
        }
    }

    // --- Note Editor Dialog ---
    if (showEditorDialog) {
        AlertDialog(
            onDismissRequest = { showEditorDialog = false },
            containerColor = SurfaceCard,
            title = {
                Text(
                    text = if (activeNoteToEdit == null) "New Note" else "Edit Note",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Title", color = TextSecondary, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPrimary,
                            unfocusedBorderColor = SurfaceCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        label = { Text("Content", color = TextSecondary, fontSize = 12.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        maxLines = 8,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPrimary,
                            unfocusedBorderColor = SurfaceCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editTitle.isNotBlank() || editContent.isNotBlank()) {
                            if (activeNoteToEdit == null) {
                                val newNote = NoteItem(
                                    title = editTitle.ifBlank { "Untitled Note" },
                                    content = editContent,
                                    category = editCategory
                                )
                                NotesRepository.addNote(context, newNote)
                            } else {
                                val updated = activeNoteToEdit!!.copy(
                                    title = editTitle.ifBlank { "Untitled Note" },
                                    content = editContent,
                                    category = editCategory,
                                    timestamp = System.currentTimeMillis()
                                )
                                NotesRepository.updateNote(context, updated)
                            }
                            notes = NotesRepository.getNotes(context)
                        }
                        showEditorDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Save", color = PureWhite, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditorDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
fun NoteCard(
    note: NoteItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, SurfaceCardBorder, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = SurfaceCard)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = note.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(SurfaceCardElevated)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(text = note.category, fontSize = 10.sp, color = TextMuted)
                }
            }

            if (note.content.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = note.content,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateFormat.format(Date(note.timestamp)),
                    fontSize = 10.sp,
                    color = TextMuted
                )
                Text(
                    text = "Delete",
                    fontSize = 11.sp,
                    color = AccentError,
                    modifier = Modifier
                        .clickable { onDelete() }
                        .padding(4.dp)
                )
            }
        }
    }
}
