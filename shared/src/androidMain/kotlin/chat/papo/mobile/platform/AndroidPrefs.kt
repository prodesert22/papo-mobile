package chat.papo.mobile.platform

import android.content.Context
import chat.papo.mobile.security.AppPrefs

class AndroidPrefs(context: Context) : AppPrefs {
    private val prefs = context.getSharedPreferences("papo.prefs", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
