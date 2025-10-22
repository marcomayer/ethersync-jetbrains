package io.github.ethersync

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

class UnfollowPeerAction : AnAction(
   "Stop following",
   "Stop following the currently tracked collaborator",
   AllIcons.Actions.Close,
) {
   override fun actionPerformed(e: AnActionEvent) {
      val project = e.project ?: return
      project.service<EthersyncService>().stopFollowingPeer()
   }
}
