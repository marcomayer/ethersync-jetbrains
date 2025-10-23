package io.github.ethersync.sync

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.rd.util.withUiContext
import com.intellij.ui.JBColor
import com.intellij.util.io.await
import io.github.ethersync.protocol.CursorEvent
import io.github.ethersync.protocol.CursorRequest
import io.github.ethersync.protocol.RemoteEthersyncClientProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import java.util.concurrent.atomic.AtomicBoolean
import java.awt.Graphics
import java.awt.Graphics2D
import java.util.*
import kotlin.collections.HashMap
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.editor.ScrollType
import java.io.File

class Cursortracker(
   private val project: Project,
   private val cs: CoroutineScope,
) : CaretListener, SelectionListener {

   companion object {
      private val LOG = logger<Cursortracker>()
   }

   private data class Key(val documentUri: String, val user: String)
   private val highlighter = HashMap<Key, List<RangeHighlighter>>()
   data class RemoteCursorInfo(
      val userId: String,
      val displayName: String,
      val documentUri: String,
      val ranges: List<Range>,
   )
   private data class RemoteCursorState(
      val name: String?,
      val documentUri: String,
      val ranges: List<Range>,
   )
   private val remoteCursors: MutableMap<String, RemoteCursorState> = HashMap()
   @Volatile
   private var followingUserId: String? = null
   private val ignoreLocalCaretEvent = AtomicBoolean(false)

   var remoteProxy: RemoteEthersyncClientProtocol? = null

   private fun displayNameFor(name: String?, userId: String): String {
      return name?.takeIf { it.isNotBlank() } ?: userId
   }

   fun listRemoteCursors(): List<RemoteCursorInfo> {
      val snapshot: List<Pair<String, RemoteCursorState>> = synchronized(remoteCursors) {
         remoteCursors.entries.map { it.key to it.value }
      }
      return snapshot
         .map { (userId, state) ->
            RemoteCursorInfo(
               userId,
               displayNameFor(state.name, userId),
               state.documentUri,
               state.ranges,
            )
         }
         .sortedBy { it.displayName.lowercase(Locale.getDefault()) }
   }

   fun unfollow() {
      followingUserId = null
   }

   fun currentFollowedUser(): String? = followingUserId

   fun displayNameForUser(userId: String): String? {
      val state = synchronized(remoteCursors) { remoteCursors[userId] } ?: return null
      return displayNameFor(state.name, userId)
   }

   fun follow(userId: String): Boolean {
      val state = synchronized(remoteCursors) { remoteCursors[userId] } ?: return false
      followingUserId = userId
      cs.launch {
         jumpToRemoteCursor(userId, state)
      }
      return true
   }

   fun stopFollowing(reason: String) {
      followingUserId = null
      LOG.debug("Stopped following: $reason")
   }

   private suspend fun jumpToRemoteCursor(userId: String, state: RemoteCursorState) {
      if (state.ranges.isEmpty()) {
         return
      }
      withUiContext {
         val vf = findOrRefreshVirtualFile(state.documentUri) ?: return@withUiContext
         val editors = FileEditorManager.getInstance(project).openFile(vf, true)
         val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull() ?: return@withUiContext
         val editor = textEditor.editor
         val range = state.ranges.last()
         val logicalStart = LogicalPosition(range.start.line, range.start.character)
         val logicalEnd = LogicalPosition(range.end.line, range.end.character)

         ignoreLocalCaretEvent.set(true)
         try {
            val caret = editor.caretModel.primaryCaret
            caret.moveToLogicalPosition(logicalStart)
            if (range.start != range.end) {
               val startOffset = editor.logicalPositionToOffset(logicalStart)
               val endOffset = editor.logicalPositionToOffset(logicalEnd)
               caret.setSelection(startOffset, endOffset)
            } else {
               caret.removeSelection()
            }
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
         } finally {
           ignoreLocalCaretEvent.set(false)
         }
      }
   }

   private fun findOrRefreshVirtualFile(documentUri: String): VirtualFile? {
      val manager = VirtualFileManager.getInstance()
      manager.findFileByUrl(documentUri)?.let { return it }
      manager.refreshAndFindFileByUrl(documentUri)?.let { return it }

      if (documentUri.startsWith("file://")) {
         val path = VfsUtilCore.urlToPath(documentUri)
         if (path.isNotBlank()) {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(path))?.let { return it }
         }
      }

      return null
   }

   private fun rememberRemoteCursor(cursorEvent: CursorEvent): RemoteCursorState {
      val state = RemoteCursorState(cursorEvent.name, cursorEvent.documentUri, cursorEvent.ranges)
      synchronized(remoteCursors) {
         remoteCursors[cursorEvent.userId] = state
      }
      return state
   }

   fun handleRemoteCursorEvent(cursorEvent: CursorEvent) {

      val state = rememberRemoteCursor(cursorEvent)
      val fileEditor = FileEditorManager.getInstance(project)
         .allEditors
         .filterIsInstance<TextEditor>()
         .filter { editor -> editor.file.canonicalFile != null }
         .firstOrNull { editor -> editor.file.canonicalFile!!.url == cursorEvent.documentUri }
         ?: run {
            if (followingUserId == cursorEvent.userId) {
               cs.launch {
                  jumpToRemoteCursor(cursorEvent.userId, state)
               }
            }
            return
         }

      val key = Key(cursorEvent.documentUri, cursorEvent.userId)
      val editor = fileEditor.editor

      cs.launch {
         withUiContext {
            synchronized(highlighter) {
               val markupModel = editor.markupModel

               val previous = highlighter.remove(key)
               if (previous != null) {
                  for (hl in previous) {
                     markupModel.removeHighlighter(hl)
                  }
               }

               val newHighlighter = LinkedList<RangeHighlighter>()
               for((i, range) in cursorEvent.ranges.withIndex()) {
                  val startPosition = editor.logicalPositionToOffset(LogicalPosition(range.start.line, range.start.character))
                  val endPosition = editor.logicalPositionToOffset(LogicalPosition(range.end.line, range.end.character))

                  val textAttributes = TextAttributes().apply {
                     effectType = EffectType.ROUNDED_BOX
                     effectColor = JBColor(JBColor.YELLOW, JBColor.DARK_GRAY)
                  }
                  val hl = markupModel.addRangeHighlighter(
                     startPosition,
                     endPosition,
                     HighlighterLayer.ADDITIONAL_SYNTAX,
                     textAttributes,
                     HighlighterTargetArea.EXACT_RANGE
                  ).apply {
                     customRenderer = object : CustomHighlighterRenderer {
                        override fun paint(editor: Editor, rl: RangeHighlighter, g: Graphics) {
                           if (i > 0) {
                              return
                           }

                           if (g is Graphics2D) {
                              val position = editor.offsetToVisualPosition(rl.endOffset)
                              val endOfLineOffset = editor.document.getLineEndOffset(position.line)
                              val endOfLinePosition = editor.offsetToVisualPosition(endOfLineOffset)
                              val endOfLineVisualPosition = editor.visualPositionToXY(endOfLinePosition)

                              val font = editor.colorsScheme.getFont(EditorFontType.PLAIN);
                              g.font = font

                              val metrics = g.getFontMetrics(font)
                              val bounds = metrics.getStringBounds(cursorEvent.name, g)

                              g.drawString(cursorEvent.name, endOfLineVisualPosition.x + 10f, endOfLineVisualPosition.y + bounds.height.toFloat())
                           }
                        }
                     }
                  }

                  newHighlighter.add(hl)
               }
               highlighter[key] = newHighlighter
            }

            if (followingUserId == cursorEvent.userId) {
               jumpToRemoteCursor(cursorEvent.userId, state)
            }
         }
      }
   }

   override fun caretPositionChanged(event: CaretEvent) {
      if (ignoreLocalCaretEvent.get()) {
         return
      }

      followingUserId?.let {
         stopFollowing("local caret move")
      }
      sendCursorUpdate(event.editor)
   }

   override fun selectionChanged(e: SelectionEvent) {
      if (ignoreLocalCaretEvent.get()) {
         return
      }

      followingUserId?.let {
         stopFollowing("local selection change")
      }
      sendCursorUpdate(e.editor)
   }

   private fun sendCursorUpdate(editor: Editor) {
      val canonicalFile = editor.virtualFile?.canonicalFile ?: return
      val uri = canonicalFile.url

      val ranges = editor.caretModel
         .allCarets
         .map { caret ->
            val selectionStart = caret.selectionStartPosition
            val selectionEnd = caret.selectionEndPosition
            if (caret.hasSelection() && selectionStart != null && selectionEnd != null) {
               Range(
                  Position(selectionStart.line, selectionStart.column),
                  Position(selectionEnd.line, selectionEnd.column),
               )
            } else {
               val pos = caret.logicalPosition
               Range(
                  Position(pos.line, pos.column),
                  Position(pos.line, pos.column),
               )
            }
         }

      launchCursorRequest(CursorRequest(uri, ranges))
   }

   private fun launchCursorRequest(cursorRequest: CursorRequest) {
      val remoteProxy = remoteProxy ?: return
      cs.launch {
         try {
            remoteProxy.cursor(cursorRequest).await()
         } catch (e: ResponseErrorException) {
            TODO("not yet implemented: notify about an protocol error")
         }
      }
   }

   suspend fun clear() {
      remoteProxy = null
      withUiContext {
         synchronized(highlighter) {
            for (entry in highlighter) {
               val fileEditor = FileEditorManager.getInstance(project)
                  .allEditors
                  .filterIsInstance<TextEditor>()
                  .filter { editor -> editor.file.canonicalFile != null }
                  .firstOrNull { editor -> editor.file.canonicalFile!!.url == entry.key.documentUri } ?: continue

               for (rangeHighlighter in entry.value) {
                  fileEditor.editor.markupModel.removeHighlighter(rangeHighlighter)
               }
            }

            highlighter.clear()
         }
      }
      synchronized(remoteCursors) {
         remoteCursors.clear()
      }
      followingUserId = null
   }
}
