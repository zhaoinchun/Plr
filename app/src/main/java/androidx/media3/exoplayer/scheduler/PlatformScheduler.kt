package androidx.media3.exoplayer.scheduler

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.annotation.RequiresPermission
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util.startForegroundService

class PlatformScheduler @RequiresPermission(Manifest.permission.RECEIVE_BOOT_COMPLETED) constructor(
    context: Context,
    private val jobId: Int
) : Scheduler {
    private val jobServiceComponentName =
        ComponentName(context.applicationContext, PlatformSchedulerService::class.java)
    private val jobScheduler: JobScheduler =
        context.applicationContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    override fun schedule(
        requirements: Requirements,
        servicePackage: String,
        serviceAction: String
    ): Boolean {
        val jobInfo: JobInfo =
            buildJobInfo(
                jobId,
                jobServiceComponentName,
                requirements,
                serviceAction,
                servicePackage
            )
        val result = jobScheduler.schedule(jobInfo)
        return result == JobScheduler.RESULT_SUCCESS
    }

    override fun cancel(): Boolean {
        jobScheduler.cancel(jobId)
        return true
    }

    override fun getSupportedRequirements(requirements: Requirements): Requirements {
        return requirements.filterRequirements(SUPPORTED_REQUIREMENTS)
    }

    class PlatformSchedulerService : JobService() {
        override fun onStartJob(params: JobParameters): Boolean {
            val extras = params.extras
            val requirements = Requirements(extras.getInt(KEY_REQUIREMENTS))
            val notMetRequirements = requirements.getNotMetRequirements(this)
            if (notMetRequirements == 0) {
                val serviceAction = extras.getString(KEY_SERVICE_ACTION)
                val servicePackage = extras.getString(KEY_SERVICE_PACKAGE)
                val intent = Intent(serviceAction).setPackage(servicePackage)
                startForegroundService(this, intent)
            } else {
                w(TAG, "Requirements not met: $notMetRequirements")
                jobFinished(params, true)
            }
            return false
        }

        override fun onStopJob(params: JobParameters?): Boolean {
            return false
        }
    }

    companion object {
        private const val TAG = "PlatformScheduler"
        private const val KEY_SERVICE_ACTION = "service_action"
        private const val KEY_SERVICE_PACKAGE = "service_package"
        private const val KEY_REQUIREMENTS = "requirements"
        private val SUPPORTED_REQUIREMENTS: Int = (Requirements.NETWORK
                or Requirements.NETWORK_UNMETERED
                or Requirements.DEVICE_IDLE
                or Requirements.DEVICE_CHARGING
                or (if (Build.VERSION.SDK_INT >= 26) Requirements.DEVICE_STORAGE_NOT_LOW else 0))

        // @RequiresPermission constructor annotation should ensure the permission is present.
        private fun buildJobInfo(
            jobId: Int,
            jobServiceComponentName: ComponentName,
            requirements: Requirements,
            serviceAction: String?,
            servicePackage: String?
        ): JobInfo {
            val filteredRequirements = requirements.filterRequirements(SUPPORTED_REQUIREMENTS)
            if (filteredRequirements != requirements) {
                w(
                    TAG,
                    "Ignoring unsupported requirements: "
                            + (filteredRequirements.requirements xor requirements.requirements)
                )
            }

            val builder = JobInfo.Builder(jobId, jobServiceComponentName)
            if (requirements.isUnmeteredNetworkRequired) {
                builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            } else if (requirements.isNetworkRequired) {
                builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            }
            builder.setRequiresDeviceIdle(requirements.isIdleRequired)
            builder.setRequiresCharging(requirements.isChargingRequired)
            if (Build.VERSION.SDK_INT >= 26 && requirements.isStorageNotLowRequired) {
                builder.setRequiresStorageNotLow(true)
            }
            builder.setPersisted(true)

            val extras = PersistableBundle()
            extras.putString(KEY_SERVICE_ACTION, serviceAction)
            extras.putString(KEY_SERVICE_PACKAGE, servicePackage)
            extras.putInt(KEY_REQUIREMENTS, requirements.requirements)
            builder.setExtras(extras)

            return builder.build()
        }
    }
}
