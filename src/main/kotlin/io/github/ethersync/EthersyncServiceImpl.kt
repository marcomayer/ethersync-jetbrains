package io.github.ethersync

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.io.await
import com.intellij.util.io.awaitExit
import com.intellij.util.io.readLineAsync
import io.github.ethersync.protocol.*
import io.github.ethersync.settings.AppSettings
import io.github.ethersync.sync.Changetracker
import io.github.ethersync.sync.Cursortracker
import io.github.ethersync.ui.ToolWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Paths
import java.util.concurrent.Executors

private val LOG = logger<EthersyncServiceImpl>()

@Service(Service.Level.PROJECT)
class EthersyncServiceImpl(
   private val project: Project,
   private val cs: CoroutineScope,
)  : EthersyncService {

   private var launcher: Launcher<RemoteEthersyncClientProtocol>? = null
   private var daemonProcess: ColoredProcessHandler? = null
   private var clientProcess: Process? = null

   private val cursortracker: Cursortracker = Cursortracker(project, cs)
   private val changetracker: Changetracker = Changetracker(project, cs, cursortracker)

   init {
      val bus = project.messageBus.connect()
      bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
         override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
            val canonicalFile = file.canonicalFile ?: return
            launchDocumentOpenRequest(canonicalFile)
         }

         override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
            val canonicalFile = file.canonicalFile ?: return
            launchDocumentCloseNotification(canonicalFile.url)
         }
      })

      bus.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
         override fun after(events: MutableList<out VFileEvent>) {
            for (event in events) {
               val file = event.file ?: continue
              val canonical = file.canonicalFile ?: continue
              val uri = canonical.url
              if (changetracker.isTracking(uri)) {
                  FileDocumentManager.getInstance().getDocument(canonical)?.let { document ->
                     cs.launch {
                        withContext(Dispatchers.EDT) {
                           changetracker.handleRemoteEditEvent(
                              EditEvent(uri, 0u, emptyList())
                           )
                        }
                     }
                  }
               }
            }
         }
      })

      for (editor in FileEditorManager.getInstance(project).allEditors) {
         if (editor is TextEditor) {
            val file = editor.file ?: continue
            if (!file.exists()) {
               continue
            }
            editor.editor.caretModel.addCaretListener(cursortracker)
            editor.editor.document.addDocumentListener(changetracker)
            editor.editor.selectionModel.addSelectionListener(cursortracker)
         }
      }

      EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
         override fun editorCreated(event: EditorFactoryEvent) {
            val file = event.editor.virtualFile ?: return
            if (!file.exists()) {
               return
            }

            event.editor.caretModel.addCaretListener(cursortracker)
            event.editor.document.addDocumentListener(changetracker)
            event.editor.selectionModel.addSelectionListener(cursortracker)
         }

         override fun editorReleased(event: EditorFactoryEvent) {
            val file = event.editor.virtualFile ?: return
            if (!file.exists()) {
               return
            }

            event.editor.caretModel.removeCaretListener(cursortracker)
            event.editor.document.removeDocumentListener(changetracker)
            event.editor.selectionModel.removeSelectionListener(cursortracker)
         }
      }, project)

      ProjectManager.getInstance().addProjectManagerListener(project, object: ProjectManagerListener {
         override fun projectClosingBeforeSave(project: Project) {
            shutdown()
         }
      })
   }

   override fun shutdown() {
      cs.launch {
         shutdownImpl()
      }
   }

   private suspend fun shutdownImpl() {
      clientProcess?.let {
         it.destroy()
         it.awaitExit()
         clientProcess = null
      }
      daemonProcess?.let {
         it.detachProcess()
         it.process.destroy()
         it.process.awaitExit()
         daemonProcess = null
      }
      changetracker.clear()
      cursortracker.clear()
   }

   override fun start(peer: String?) {
      val cmd = GeneralCommandLine(AppSettings.getInstance().state.ethersyncBinaryPath)
      if (peer.isNullOrBlank()) {
         cmd.addParameter("share")
      } else {
         cmd.addParameter("join")
         cmd.addParameter(peer.trim())
      }
      launchDaemon(cmd)
   }

   override fun startWithCustomCommandLine(commandLine: String) {
      // TODO: splitting by " " is probably insufficient if there is an argument with spaces in it…
      val cmd = GeneralCommandLine(commandLine.split(" "))

      launchDaemon(cmd)
   }

   override fun followPeer() {
      val remoteCursors = cursortracker.listRemoteCursors()
      if (remoteCursors.isEmpty()) {
         Messages.showInfoMessage(project, "No remote cursors available to follow.", "Ethersync")
         return
      }

      val optionStrings = remoteCursors.map { info ->
         val location = formatLocation(info.documentUri, info.ranges)
         "${info.displayName} — $location"
      }

      val optionArray = optionStrings.toTypedArray()
      val selectedIndex =
         if (optionArray.size == 1) {
            0
         } else {
            val index = Messages.showChooseDialog(
               project,
               "Select the collaborator whose cursor you want to follow.",
               "Follow Peer",
               null,
               optionArray,
               optionArray.first(),
            )
            if (index < 0) {
               return
            }
            index
         }

      val selected = remoteCursors[selectedIndex]
      if (cursortracker.follow(selected.userId)) {
         Messages.showInfoMessage(project, "Following ${selected.displayName}.", "Ethersync")
      } else {
         Messages.showWarningDialog(project, "The selected cursor is no longer available.", "Ethersync")
      }
   }

   override fun stopFollowingPeer() {
      val current = cursortracker.currentFollowedUser()
      if (current == null) {
         Messages.showInfoMessage(project, "You are not following any collaborator.", "Ethersync")
         return
      }

      cursortracker.unfollow()
      val displayName = cursortracker.displayNameForUser(current) ?: "collaborator"
      Messages.showInfoMessage(project, "Stopped following $displayName.", "Ethersync")
   }

   private fun launchDaemon(cmd: GeneralCommandLine) {
      val projectDirectory = File(project.basePath!!)
      val ethersyncDirectory = File(projectDirectory, ".ethersync")
      cmd.workDirectory = projectDirectory

      cs.launch {
         shutdownImpl()

         if (!ethersyncDirectory.exists()) {
            LOG.debug("Creating ethersync directory")
            ethersyncDirectory.mkdir()
         }

         withContext(Dispatchers.EDT) {
            daemonProcess = ColoredProcessHandler(cmd)

            daemonProcess!!.addProcessListener(object : ProcessAdapter() {
               override fun processTerminated(event: ProcessEvent) {
                  shutdown()
               }
            })

            val tw = ToolWindowManager.getInstance(project).getToolWindow("ethersync")!!
            val toolWindow = tw.contentManager.findContent("Daemon")!!.component
            if (toolWindow is ToolWindow) {
               toolWindow.attachToProcess(daemonProcess!!)
            }

            tw.show()

            daemonProcess!!.startNotify()
            cs.launch {
               waitForSocketAndLaunchClient(projectDirectory)
            }
         }

      }
   }

   private fun createProtocolHandler(): EthersyncEditorProtocol {

      return object : EthersyncEditorProtocol {
         override fun cursor(cursorEvent: CursorEvent) {
            cursortracker.handleRemoteCursorEvent(cursorEvent)
         }

         override fun edit(editEvent: EditEvent) {
            changetracker.handleRemoteEditEvent(editEvent)
         }

      }
   }
   private suspend fun waitForSocketAndLaunchClient(projectDirectory: File) {
      val socketFile = File(projectDirectory, ".ethersync/socket")
      while (clientProcess == null && daemonProcess != null) {
         if (socketFile.exists()) {
            launchEthersyncClient(projectDirectory)
            return
         }
         delay(200)
      }
   }

   private fun launchEthersyncClient(projectDirectory: File) {
      if (clientProcess != null) {
         return
      }

      cs.launch {
         LOG.info("Starting ethersync client")
         // TODO: try catch not existing binary
         val clientProcessBuilder = ProcessBuilder(
            AppSettings.getInstance().state.ethersyncBinaryPath,
            "client")
               .directory(projectDirectory)
         clientProcess = clientProcessBuilder.start()
         val clientProcess = clientProcess!!

         val ethersyncEditorProtocol = createProtocolHandler()
         launcher = Launcher.createIoLauncher(
               ethersyncEditorProtocol,
               RemoteEthersyncClientProtocol::class.java,
               clientProcess.inputStream,
               clientProcess.outputStream,
               Executors.newCachedThreadPool(),
               { c -> c },
               { _ -> run {} }
         )

         val listening = launcher!!.startListening()
         cursortracker.remoteProxy = launcher!!.remoteProxy
         changetracker.remoteProxy = launcher!!.remoteProxy

         val fileEditorManager = FileEditorManager.getInstance(project)
         for (file in fileEditorManager.openFiles) {
            val canonicalFile = file.canonicalFile ?: continue
            launchDocumentOpenRequest(canonicalFile)
         }

         clientProcess.awaitExit()

         listening.cancel(true)
         listening.await()

         if (clientProcess.exitValue() != 0) {
            val stderr = BufferedReader(InputStreamReader(clientProcess.errorStream))
            stderr.use {
               while (true) {
                  val line = stderr.readLineAsync() ?: break;
                  LOG.trace(line)
               }
            }
         }
      }
   }

   fun launchDocumentCloseNotification(fileUri: String) {
      val launcher = launcher ?: return
      cs.launch {
         launcher.remoteProxy.close(DocumentRequest(fileUri))
         changetracker.closeFile(fileUri)
      }
   }

   fun launchDocumentOpenRequest(file: VirtualFile) {
      val launcher = launcher ?: return
      val fileUri = file.url
      cs.launch {
         try {
            val content = withContext(Dispatchers.EDT) {
               val document = FileDocumentManager.getInstance().getDocument(file)
               document?.text
            }

            if (content == null) {
               LOG.warn("Unable to read content for $fileUri; skipping open request")
               return@launch
            }

            changetracker.openFile(fileUri)
            launcher.remoteProxy.open(DocumentRequest(fileUri, content)).await()
         } catch (e: ResponseErrorException) {
            TODO("not yet implemented: notify about an protocol error")
         }
      }
   }

   private fun formatLocation(documentUri: String, ranges: List<Range>): String {
      val range = ranges.lastOrNull()
      val line = range?.start?.line?.plus(1) ?: "?"

      val path = runCatching { VfsUtilCore.urlToPath(documentUri) }.getOrNull()
      val presentablePath = path ?: documentUri
      val relative = project.basePath?.let { base ->
         runCatching {
            val basePath = Paths.get(base)
            val filePath = Paths.get(presentablePath)
            basePath.relativize(filePath).toString()
         }.getOrNull()
      }

      val displayPath = (relative ?: presentablePath).ifEmpty { presentablePath }
      return "$displayPath:$line"
   }

}
