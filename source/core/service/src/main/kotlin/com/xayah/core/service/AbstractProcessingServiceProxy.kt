package com.xayah.core.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.withMainContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

abstract class AbstractProcessingServiceProxy {
    private var mBinder: IBinder? = null
    private var mService: AbstractProcessingService? = null
    private var mConnection: ServiceConnection? = null
    abstract val context: Context
    abstract val intent: Intent

    private suspend fun bindService(): AbstractProcessingService = suspendCancellableCoroutine { continuation ->
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                mBinder = service

                mService = (mBinder as AbstractProcessingService.OperationLocalBinder).getService()
                if (continuation.isActive) continuation.resume(mService!!)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                mBinder = null
                mService = null
                val msg = "Service disconnected."
                if (continuation.isActive) continuation.resumeWithException(RemoteException(msg))
            }

            override fun onBindingDied(name: ComponentName) {
                mBinder = null
                mService = null
                val msg = "Binding died."
                if (continuation.isActive) continuation.resumeWithException(RemoteException(msg))
            }

            override fun onNullBinding(name: ComponentName) {
                mBinder = null
                mService = null
                val msg = "Null binding."
                if (continuation.isActive) continuation.resumeWithException(RemoteException(msg))
            }
        }
        mConnection = connection
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            mConnection = null
            continuation.resumeWithException(RemoteException("Unable to bind processing service."))
        }
        continuation.invokeOnCancellation {
            Handler(Looper.getMainLooper()).post {
                if (mConnection === connection) destroyService()
            }
        }
    }

    /**
     * Destroy the service.
     */
    fun destroyService(clearNotification: Boolean = false) {
        if (clearNotification) NotificationUtil.cancel(context)
        if (mConnection != null)
            context.unbindService(mConnection!!)
        mService?.stopSelf()
        mBinder = null
        mService = null
        mConnection = null
    }

    private suspend fun getService(): AbstractProcessingService = withMainContext {
        if (mService == null) {
            bindService()
        } else if (mBinder!!.isBinderAlive.not()) {
            destroyService()
            bindService()
        } else {
            mService!!
        }
    }

    suspend fun initialize(cloudName: String? = null, backupDir: String? = null) =
        getService().initialize(cloudName, backupDir)

    suspend fun preprocessing() = getService().preprocessing()

    suspend fun processing() = getService().processing()

    suspend fun postProcessing() = getService().postProcessing()

    suspend fun cleanup() = mService?.cleanup()
}
