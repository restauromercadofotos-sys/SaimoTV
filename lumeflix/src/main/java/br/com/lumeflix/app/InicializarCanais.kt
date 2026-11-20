package br.com.lumeflix.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@UnstableApi
class InicializarCanais : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.media.tv.action.INITIALIZE_PROGRAMS") agendar(context)
    }

    companion object {
        fun agendar(context: Context) {
            if (Build.VERSION.SDK_INT < 26 ||
                !context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return
            val jobs = context.getSystemService(JobScheduler::class.java)
            val componente = ComponentName(context, PublicarCanaisJob::class.java)
            if (jobs.getPendingJob(8101) == null)
                jobs.schedule(JobInfo.Builder(8101, componente).setOverrideDeadline(0).build())
            if (jobs.getPendingJob(8102) == null)
                jobs.schedule(JobInfo.Builder(8102, componente).setPeriodic(12 * 60 * 60 * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
        }
    }
}

@UnstableApi
class PublicarCanaisJob : JobService() {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tarefas = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        tarefas[params.jobId] = escopo.launch {
            val completo = CanalNaTv.publicar(applicationContext)
            tarefas.remove(params.jobId)
            jobFinished(params, !completo)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        tarefas.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        escopo.cancel()
        super.onDestroy()
    }
}
