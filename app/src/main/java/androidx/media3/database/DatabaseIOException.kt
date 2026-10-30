package androidx.media3.database

import android.database.SQLException
import java.io.IOException

class DatabaseIOException : IOException {
    constructor(cause: SQLException?) : super(cause)

    constructor(cause: SQLException?, message: String?) : super(message, cause)
}
