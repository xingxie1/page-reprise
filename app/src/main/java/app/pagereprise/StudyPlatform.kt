package app.pagereprise

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

class StudyViewModel(app: Application) : AndroidViewModel(app) {
    private val platform = StudyPlatform(app)
    val model = StudyModel(platform, viewModelScope)
    override fun onCleared() {
        model.close()
        platform.store.db.close()
        super.onCleared()
    }
}

class StudyPlatform(private val app: Application) {
    val store = Store(app)
    val vault = Vault(app)
    val prefs = AppPreferences(app)
    val cacheDir get() = app.cacheDir
    fun media(owner: String) = Media(app, owner)
    fun scheduleCleanup(owner: String, deadline: Long) = TrashCleanup.schedule(app, owner, deadline)
}

class AppPreferences(app: Application) {
    private val prefs = app.getSharedPreferences("preferences", 0)
    fun getString(key: String, fallback: String?) = prefs.getString(key, fallback)
    fun getBoolean(key: String, fallback: Boolean) = prefs.getBoolean(key, fallback)
    fun getLong(key: String, fallback: Long) = prefs.getLong(key, fallback)
    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
}
