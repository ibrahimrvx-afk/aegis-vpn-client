override fun startup(): Long = 0L

override fun shutdown(): Long {
    if (!userInitiatedDisconnect) handleUnexpectedCoreDrop()
    return 0L
}

override fun onEmitStatus(code: Int, message: String?): Long {
    broadcastStatus(message ?: "status $code")
    return 0L
}
