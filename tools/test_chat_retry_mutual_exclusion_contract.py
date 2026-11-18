from pathlib import Path

source = Path("app/src/main/java/com/tacresq/app/ui/chat/IncidentChatScreen.kt").read_text()

restore = source.split("repo.pendingChatImage()", 1)[1].split(".onFailure", 1)[0]
assert "saved != null && image != null" in restore, "simultaneous restored text and image retries must be blocked"
assert "Both a text message and an image send are unresolved" in restore

send_image = source.split("fun sendImage(", 1)[1].split("\n    suspend fun downloadImage", 1)[0]
assert "_state.value.restoringPending" in send_image, "image send must wait for pending-state restoration"
assert "pendingTextMessage != null || pendingClientMessageId != null" in send_image, "image send must not overwrite an unresolved text retry"
assert "Retry that exact message before sending an image" in send_image

send_text = source.split("fun send(incidentId:", 1)[1].split("\n    fun setImageAttachment", 1)[0]
assert "pendingImageRecord != null" in send_text, "text send must remain blocked while an image retry is unresolved"
print("Chat retry mutual-exclusion source contract passed.")
