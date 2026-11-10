package com.xayah.core.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.xayah.core.model.PackageTaskRequest
import com.xayah.core.util.withMainContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

abstract class AbstractProcessingServiceProxy {
    private companion object {
        var owner: AbstractProcessingServiceProxy? = null
    }

    private var mBinder: IBinder? = null
    private var mService: AbstractProcessingService? = null
    private var mConnection: ServiceConnection? = null
    abstract val context: Context
    abstract val intent: Intent

    private fun releaseOwnership() {
        if (owner === this) owner = null
    }

    private suspend fun bindService(): AbstractProcessingService = suspendCancellableCoroutine { continuation ->
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (!continuation.isActive || mConnection !== this) return
                val remote = (service as AbstractProcessingService.OperationLocalBinder).getService()
                remote.onServiceDestroyed = ::releaseOwnership
                mBinder = service
                mService = remote
                continuation.resume(remote)
            }

            private fun disconnected() {
                if (mConnection !== this) return
                mBinder = null
                mService = null
                if (continuation.isActive) continuation.resumeWithException(RemoteException(context.getString(R.string.processing_service_disconnected)))
            }

            override fun onServiceDisconnected(name: ComponentName) = disconnected()
            override fun onBindingDied(name: ComponentName) = disconnected()
            override fun onNullBinding(name: ComponentName) = disconnected()
        }
        mConnection = connection
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            continuation.resumeWithException(RemoteException(context.getString(R.string.processing_service_bind_failed)))
        }
    }

    fun destroyService() {
        val bound = mService
        try {
            mConnection?.let(context::unbindService)
        } finally {
            mConnection = null
            mBinder = null
            mService = null
            if (bound == null) releaseOwnership() else bound.stopSelf()
        }
    }

    private suspend fun activeService(): AbstractProcessingService = withMainContext {
        mService?.takeIf { mBinder?.isBinderAlive == true }
            ?: throw RemoteException(context.getString(R.string.processing_service_disconnected))
    }

    suspend fun initialize(cloudName: String?, backupDir: String?, request: PackageTaskRequest?): Long {
        val service = withMainContext {
            check(owner == null) { context.getString(R.string.processing_task_busy) }
            owner = this@AbstractProcessingServiceProxy
            withTimeoutOrNull(15_000) { bindService() }
                ?: throw RemoteException(context.getString(R.string.processing_service_timeout))
        }
        return service.initialize(cloudName, backupDir, request)
    }

    suspend fun preprocessing() = activeService().preprocessing()

    suspend fun processing() = activeService().processing()

    suspend fun postProcessing() = activeService().postProcessing()

    suspend fun cleanup() = mService?.cleanup()
    suspend fun turnOffScreen() = mService?.turnOffScreen()
}
