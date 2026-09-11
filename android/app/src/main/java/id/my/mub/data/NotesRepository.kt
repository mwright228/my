package id.my.mub.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class NoteItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val category: String = "Personal",
    val timestamp: Long = System.currentTimeMillis()
)

object NotesRepository {
    private const val FILE_NAME = "quick_notes_store.json"
    private var inMemoryNotes: MutableList<NoteItem> = mutableListOf()
    private var initialized = false

    fun getNotes(context: Context): List<NoteItem> {
        if (!initialized) {
            loadNotes(context)
            if (inMemoryNotes.isEmpty()) {
                // Pre-populate with realistic, innocent everyday notes so the app looks completely authentic
                inMemoryNotes.addAll(
                    listOf(
                        NoteItem(
                            title = "Weekend Groceries",
                            content = "• Oat milk\n• Avocados & bananas\n• Whole grain bread\n• Green tea & coffee beans\n• Olive oil",
                            category = "Personal",
                            timestamp = System.currentTimeMillis() - 86400000L * 2
                        ),
                        NoteItem(
                            title = "Project Milestones Q3",
                            content = "1. Finalize mobile responsive layout\n2. Database indexing audit\n3. Review client onboarding checklist",
                            category = "Work",
                            timestamp = System.currentTimeMillis() - 86400000L * 5
                        ),
                        NoteItem(
                            title = "Book Recommendations",
                            content = "• Atomic Habits by James Clear\n• Deep Work by Cal Newport\n• Designing Data-Intensive Applications",
                            category = "Reading",
                            timestamp = System.currentTimeMillis() - 86400000L * 9
                        )
                    )
                )
                saveNotes(context)
            }
            initialized = true
        }
        return inMemoryNotes.toList()
    }

    fun addNote(context: Context, note: NoteItem) {
        inMemoryNotes.add(0, note)
        saveNotes(context)
    }

    fun updateNote(context: Context, updated: NoteItem) {
        val idx = inMemoryNotes.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            inMemoryNotes[idx] = updated
            saveNotes(context)
        }
    }

    fun deleteNote(context: Context, id: String) {
        inMemoryNotes.removeAll { it.id == id }
        saveNotes(context)
    }

    private fun saveNotes(context: Context) {
        try {
            val arr = JSONArray()
            for (note in inMemoryNotes) {
                val obj = JSONObject().apply {
                    put("id", note.id)
                    put("title", note.title)
                    put("content", note.content)
                    put("category", note.category)
                    put("timestamp", note.timestamp)
                }
                arr.put(obj)
            }
            File(context.filesDir, FILE_NAME).writeText(arr.toString())
        } catch (e: Exception) {
            // Ignored in sandbox
        }
    }

    private fun loadNotes(context: Context) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return
            val text = file.readText()
            val arr = JSONArray(text)
            inMemoryNotes.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                inMemoryNotes.add(
                    NoteItem(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        content = obj.getString("content"),
                        category = obj.optString("category", "Personal"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            inMemoryNotes.clear()
        }
    }
}
