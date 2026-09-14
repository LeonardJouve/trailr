package ch.trailer.android.domain

import android.content.SharedPreferences

/** Minimal in-memory [SharedPreferences] so the JVM unit tests need no Android runtime. */
class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    private var cleared = false

    override fun getAll(): MutableMap<String, *> = values.toMap() as MutableMap<String, Any?>

    override fun getString(key: String, defValue: String?): String? = read(key) as String? ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        read(key) as MutableSet<String>? ?: defValues

    override fun getInt(key: String, defValue: Int): Int = read(key) as Int? ?: defValue

    override fun getLong(key: String, defValue: Long): Long = read(key) as Long? ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = read(key) as Float? ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = read(key) as Boolean? ?: defValue

    override fun contains(key: String): Boolean = read(key) != null

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private fun read(key: String): Any? = if (cleared) null else values[key]

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var pendingClear = false

        override fun putString(key: String, value: String?) = put(key, value)

        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values)

        override fun putInt(key: String, value: Int) = put(key, value)

        override fun putLong(key: String, value: Long) = put(key, value)

        override fun putFloat(key: String, value: Float) = put(key, value)

        override fun putBoolean(key: String, value: Boolean) = put(key, value)

        override fun remove(key: String) = put(key, null)

        override fun clear(): SharedPreferences.Editor {
            pendingClear = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (pendingClear) {
                values.clear()
                cleared = false
                pendingClear = false
            }
            pending.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            pending.clear()
        }

        private fun put(key: String, value: Any?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }
    }
}
