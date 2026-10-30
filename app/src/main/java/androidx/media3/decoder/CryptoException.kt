package androidx.media3.decoder

class CryptoException(
    val errorCode: Int, message: String?
) : Exception(message)
