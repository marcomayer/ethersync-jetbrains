package io.github.ethersync

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

class FollowPeerAction : AnAction(
   "Follow peer",
   "Follow another collaborator's cursor",
   AllIcons.Actions.Find,
) {
   override fun actionPerformed(e: AnActionEvent) {
      val project = e.project ?: return
      project.service<EthersyncService>().followPeer()
   }
}
