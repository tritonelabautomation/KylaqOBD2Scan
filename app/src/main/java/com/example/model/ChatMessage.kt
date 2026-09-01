package com.example.model

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val isEcuFact: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

enum class MessageSender {
    USER,
    CAR_DOCTOR
}
