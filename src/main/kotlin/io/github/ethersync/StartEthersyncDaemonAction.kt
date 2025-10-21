package io.github.ethersync

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages

class StartEthersyncDaemonAction : AnAction("Connect to peer", "Connect to an existing session or start sharing this project",
   AllIcons.CodeWithMe.CwmInvite) {

   override fun actionPerformed(e: AnActionEvent) {
      val project = e.project ?: return
      val address = Messages.showInputDialog(
         project,
         "Provide an ethersync join code or secret address. Leave empty to host a new session.",
         "Join Code",
         Icons.ToolbarIcon
      )

      if (address != null) {
         val service = project.service<EthersyncService>()

         service.start(address)
      }
   }
}
