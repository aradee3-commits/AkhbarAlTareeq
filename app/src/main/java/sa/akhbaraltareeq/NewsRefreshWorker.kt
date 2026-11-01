package sa.akhbaraltareeq

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class NewsRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        NewsRepository(applicationContext).refresh()
        Result.success()
    } catch (_: Exception) { Result.retry() }
}
