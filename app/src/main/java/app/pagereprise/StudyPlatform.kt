package app.pagereprise

import android.app.Application
import app.pagereprise.core.Edition
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
    @Suppress("DEPRECATION")
    fun updates(scope: kotlinx.coroutines.CoroutineScope): AppUpdates? {
        if (app.packageName != "app.pagereprise") return null
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        return AppUpdates(requireNotNull(info.versionName), Edition.ANDROID,
            java.io.File(app.cacheDir, "updates"), scope, info.versionCode)
    }
    fun media(owner: String) = Media(app, owner)
    fun scheduleCleanup(owner: String, deadline: Long) = TrashCleanup.schedule(app, owner, deadline)
}

class AppPreferences(app: Application) {
    private val prefs = app.getSharedPreferences("preferences", 0)
    fun getString(key: String, fallback: String?) = prefs.getString(key, fallback)
    fun getLong(key: String, fallback: Long) = prefs.getLong(key, fallback)
    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
}
