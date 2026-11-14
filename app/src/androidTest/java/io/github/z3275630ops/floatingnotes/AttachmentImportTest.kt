package io.github.z3275630ops.floatingnotes

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.data.local.AttachmentStore
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import io.github.z3275630ops.floatingnotes.feature.share.ShareCaptureActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

class AttachmentImportTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val root get() = File(context.filesDir, "attachments")

    @Test fun originalHashAndSmallThumbnailSurviveLossOfSource() = runBlocking {
        withStore { store, _ ->
            val source = imageFile()
            try {
                val expected = source.readBytes()
                val item = store.import(uri(source), "image/png")
                context.revokeUriPermission(uri(source), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                assertTrue(source.delete())
                val local = requireNotNull(store.find(item.id))
                assertArrayEquals(expected, store.original(local).readBytes())
                assertEquals(expected.size.toLong(), local.bytes)
                assertEquals(MessageDigest.getInstance("SHA-256").digest(expected)
                    .joinToString("") { "%02x".format(it) }, local.sha256)
                val thumbnail = requireNotNull(store.thumbnail(local.id))
                assertTrue(thumbnail.width <= 256 && thumbnail.height <= 256)
                thumbnail.recycle()
            } finally {
                source.delete()
            }
        }
    }

    @Test fun importsPlayableWaveWithoutDecodingAnImage() = runBlocking {
        withStore { store, _ ->
            val source = File.createTempFile("synthetic-", ".wav", root.apply { mkdirs() })
            try {
                val audioSize = 16_000
                val wave = ByteBuffer.allocate(44 + audioSize).order(ByteOrder.LITTLE_ENDIAN)
                wave.put("RIFF".toByteArray()).putInt(36 + audioSize).put("WAVEfmt ".toByteArray())
                    .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                    .put("data".toByteArray()).putInt(audioSize)
                source.writeBytes(wave.array())
                val item = store.import(uri(source), "audio/wav")
                assertTrue(item.mime.startsWith("audio/"))
                assertArrayEquals(wave.array(), store.original(item).readBytes())
                assertNull(store.thumbnail(item.id))
            } finally {
                source.delete()
            }
        }
    }

    @Test fun videoKeepsOriginalAndCreatesBoundedPreview() = runBlocking {
        withStore { store, _ ->
            val source = File.createTempFile("synthetic-", ".mp4", root.apply { mkdirs() })
            try {
                val assets = InstrumentationRegistry.getInstrumentation().context.assets
                assets.open("t10-synthetic.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
                val item = store.import(uri(source), "video/mp4")
                assertEquals("video/mp4", item.mime)
                assertArrayEquals(source.readBytes(), store.original(item).readBytes())
                val thumbnail = requireNotNull(store.thumbnail(item.id))
                assertTrue(thumbnail.width <= 256 && thumbnail.height <= 256)
                thumbnail.recycle()
            } finally {
                source.delete()
            }
        }
    }

    @Test fun corruptUnreadableAndLowSpaceLeaveNoStagingFiles() = runBlocking {
        withStore { store, directory ->
            val source = File.createTempFile("broken-", ".png", root.apply { mkdirs() }).apply { writeText("broken") }
            try {
                val lowSpace = AttachmentStore(context, directory) { 0L }
                val noSpace = runCatching { lowSpace.import(uri(source), "image/png") }.exceptionOrNull()
                assertTrue(AttachmentStore.failureMessage(noSpace as Exception).contains("空间不足"))
                assertTrue(runCatching { store.import(uri(source), "image/png") }.isFailure)
                val unreadable = Uri.parse("content://${context.packageName}.attachments/attachments/missing.png")
                assertTrue(runCatching { store.import(unreadable, "image/png") }.isFailure)
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            } finally {
                source.delete()
            }
        }
    }

    @Test fun recoveryRemovesIncompleteAndUnregisteredFilesButKeepsDraftsAndRevisions() = runBlocking {
        withStore { store, directory ->
            val source = imageFile()
            val database = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java).build()
            try {
                val repository = NotesRepository(database)
                val pending = store.import(uri(source), "image/png")
                val historical = store.import(uri(source), "image/png")
                val orphan = store.import(uri(source), "image/png")
                val stage = File(directory, "${UUID.randomUUID()}.part").apply { mkdir() }
                File(stage, "original").writeText("interrupted")

                fun document(id: String) = NoteDocument(listOf(NoteBlock.attachment("image", id))).encode()
                repository.checkpoint(repository.newDraft("pending").copy(documentJson = document(pending.id), editSeq = 1))
                val first = repository.commit(repository.newDraft("saved").copy(documentJson = document(historical.id), editSeq = 1))
                repository.commit(repository.newDraft("saved", first).copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("合成修订"))).encode(), editSeq = 1,
                ))

                store.collectUnreferenced(database)
                assertFalse(stage.exists())
                assertNull(store.find(orphan.id))
                assertNotNull(store.find(pending.id))
                assertNotNull(store.find(historical.id))
            } finally {
                database.close()
                source.delete()
            }
        }
    }

    @Test fun sharedPhotoCheckpointsAndSavesAsAnAttachmentOnlyNote() {
        val source = imageFile()
        val intent = Intent(Intent.ACTION_SEND).setClass(context, ShareCaptureActivity::class.java)
            .setType("image/png").putExtra(Intent.EXTRA_STREAM, uri(source))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val database = NotesDatabase.open(context)
        val repository = NotesRepository(database)
        val previous = runBlocking { repository.notes.first().map { it.id }.toSet() }
        val scenario = ActivityScenario.launch<ShareCaptureActivity>(intent)
        try {
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(source.name).fetchSemanticsNodes().isNotEmpty()
            }
            scenario.recreate()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(source.name).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(source.delete())
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(10_000) {
                runBlocking { repository.notes.first().any { it.id !in previous && it.sourceType == "share" } }
            }
            val saved = runBlocking { repository.notes.first().first { it.id !in previous && it.sourceType == "share" } }
            val id = NoteDocument.decode(saved.documentJson).blocks.firstNotNullOf { it.attachmentId }
            assertNotNull(runBlocking { AttachmentStore(context).find(id) })
        } finally {
            scenario.close()
            source.delete()
        }
    }

    private fun imageFile(): File = File.createTempFile("synthetic-", ".png", root.apply { mkdirs() }).apply {
        val image = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(0xFF74CCA9.toInt())
            outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            image.recycle()
        }
    }

    private fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)

    private suspend fun withStore(test: suspend (AttachmentStore, File) -> Unit) {
        val directory = File(context.cacheDir, "attachment-test-${UUID.randomUUID()}")
        try {
            test(AttachmentStore(context, directory), directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
