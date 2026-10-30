package androidx.media3.extractor.metadata.emsg

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

class EventMessageEncoder {
    private val byteArrayOutputStream: ByteArrayOutputStream
    private val dataOutputStream: DataOutputStream

    init {
        byteArrayOutputStream = ByteArrayOutputStream(512)
        dataOutputStream = DataOutputStream(byteArrayOutputStream)
    }

    fun encode(eventMessage: EventMessage): ByteArray {
        byteArrayOutputStream.reset()
        try {
            writeNullTerminatedString(dataOutputStream, eventMessage.schemeIdUri)
            val nonNullValue = if (eventMessage.value != null) eventMessage.value else ""
            writeNullTerminatedString(dataOutputStream, nonNullValue)
            dataOutputStream.writeLong(eventMessage.durationMs)
            dataOutputStream.writeLong(eventMessage.id)
            dataOutputStream.write(eventMessage.messageData)
            dataOutputStream.flush()
            return byteArrayOutputStream.toByteArray()
        } catch (e: IOException) {
            // Should never happen.
            throw RuntimeException(e)
        }
    }

    companion object {
        @Throws(IOException::class)
        private fun writeNullTerminatedString(dataOutputStream: DataOutputStream, value: String?) {
            dataOutputStream.writeBytes(value)
            dataOutputStream.writeByte(0)
        }
    }
}
