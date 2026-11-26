package app.pagereprise

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import kotlinx.coroutines.*

class TrashCleanup : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val owner = params.extras.getString("owner") ?: return false
        work = CoroutineScope(Dispatchers.Main).launch {
            try {
                val next = withContext(Dispatchers.IO) {
                    val store = Store(applicationContext)
                    try { store.maintain(owner) } finally { store.db.close() }
                }
                jobFinished(params, false)
                if (next != null) schedule(applicationContext, owner, next)
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { jobFinished(params, true) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }

    companion object {
        private const val JOB = 20701
        fun schedule(context: Context, owner: String, deadline: Long) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB)?.extras?.getLong("deadline") == deadline) return
            val delay = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
            val extras = PersistableBundle().apply { putString("owner", owner); putLong("deadline", deadline) }
            scheduler.schedule(JobInfo.Builder(JOB, ComponentName(context, TrashCleanup::class.java))
                .setExtras(extras).setPersisted(true).setMinimumLatency(delay).setOverrideDeadline(delay + 300000).build())
        }
    }
}
