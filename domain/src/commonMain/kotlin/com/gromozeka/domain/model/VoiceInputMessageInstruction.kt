package com.gromozeka.domain.model

val VoiceInputMessageInstruction = Conversation.Message.Instruction.UserInstruction(
    id = "voice-stt-source",
    title = "Voice input",
    description = "This user message was dictated through speech-to-text; tolerate likely recognition mistakes.",
)

val ActionButtonAutoMessageInstruction = Conversation.Message.Instruction.UserInstruction(
    id = "action-button-auto-mode",
    title = "Action Button auto mode",
    description = "This message came from the Action Button. Infer the expected read/write behavior from context instead of assuming the regular chat input toggle is visible. If voice output is appropriate, make ttsText directly speakable because the user may not see the screen.",
)
