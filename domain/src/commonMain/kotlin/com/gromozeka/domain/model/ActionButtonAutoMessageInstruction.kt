package com.gromozeka.domain.model

val ActionButtonAutoMessageInstruction = Conversation.Message.Instruction.UserInstruction(
    id = "action-button-auto-mode",
    title = "Action Button auto mode",
    description = "This message came from the Action Button. Infer the expected read/write behavior from context instead of assuming the regular chat input toggle is visible. If voice output is appropriate, make ttsText directly speakable because the user may not see the screen.",
)
