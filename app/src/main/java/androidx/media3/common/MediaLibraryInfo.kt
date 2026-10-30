package androidx.media3.common

object MediaLibraryInfo {
    const val TAG: String = "AndroidXMedia3"
    const val VERSION: String = "1.11.0"
    const val VERSION_SLASHY: String = "AndroidXMedia3/1.11.0"
    const val VERSION_INT: Int = 1011000300
    const val TRACE_ENABLED: Boolean = true

    private var enableWorkarounds = true

    fun enableWorkarounds(): Boolean {
        return enableWorkarounds
    }

    fun setEnableWorkarounds(enable: Boolean) {
        enableWorkarounds = enable
    }

    const val INTERFACE_VERSION: Int = 10

    private val registeredModules = HashSet<String?>()
    private var registeredModulesString = "media3.common"

    @Synchronized
    fun registeredModules(): String {
        return registeredModulesString
    }

    @Synchronized
    fun registerModule(name: String?) {
        if (registeredModules.add(name)) {
            registeredModulesString = "$registeredModulesString, $name"
        }
    }
}
