package co.aospa.glyph.Services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import co.aospa.glyph.Manager.AnimationManager
import co.aospa.glyph.Manager.StatusManager
import com.nothing.thirdparty.IGlyphService

class ThirdPartyService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val binder = object : IGlyphService.Stub() {
        override fun setFrameColors(iArray: IntArray?) {
            Log.d("ThirdPartyService", "received data: ${iArray.contentToString()}")
            AnimationManager.updateLedFrame(iArray)
        }

        override fun openSession() {
            Log.d("ThirdPartyService", "openSession")
            acquireWakeLock()
            StatusManager.setAnimationActive(true)
        }

        override fun closeSession() {
            Log.d("ThirdPartyService", "closeSession")
            StatusManager.setAnimationActive(false)
            AnimationManager.clearLEDs()
            releaseWakeLock()
        }

        override fun register(str: String) = true
        override fun registerSDK(str1: String, str2: String) = true
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d("ThirdPartyService", "onUnbind - cleaning up session")
        StatusManager.setAnimationActive(false)
        AnimationManager.clearLEDs()
        releaseWakeLock()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.d("ThirdPartyService", "onDestroy - cleaning up session")
        StatusManager.setAnimationActive(false)
        AnimationManager.clearLEDs()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ThirdPartyService::WakeLock")
            wakeLock?.acquire(60000)
            Log.d("ThirdPartyService", "WakeLock acquired with safety timeout")
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock != null) {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
            Log.d("ThirdPartyService", "WakeLock released")
        }
    }
}
