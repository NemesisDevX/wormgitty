package com.wormgitty.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Translucent, no-history activity that asks the system MediaProjection consent
 * dialog exactly once. Result is persisted via [ProjectionConsent.saveIntent]
 * so headless services can replay the grant afterwards.
 */
class ConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
        } catch (_: Exception) {
            finish()
        }
        // A blocked/invisible launch never produces a result — self-finish so
        // stale consent tasks don't stack up behind newer attempts.
        android.os.Handler(mainLooper).postDelayed({
            if (!isFinishing && !isDestroyed) {
                GrantBus.result = GrantBus.result ?: false
                GrantBus.latch.countDown()
                finish()
            }
        }, 25_000)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            // The grant token is a live Binder — keep the raw Intent in-memory;
            // cross-process parceling is unreliable on API28 anyway.
            GrantBus.intent = data
            GrantBus.resultCode = resultCode
            val saved = ProjectionConsent(this, AndroidKeystoreKeySource()).saveIntent(resultCode, data)
            android.util.Log.i("ConsentA", "granted rc=$resultCode saved=$saved")
            GrantBus.result = true
        } else {
            android.util.Log.i("ConsentA", "denied rc=$resultCode data=${data != null}")
            GrantBus.result = false
        }
        GrantBus.latch.countDown()
        finish()
    }

    override fun onDestroy() {
        GrantBus.latch.countDown() // never leave a waiter stuck
        super.onDestroy()
    }

    companion object { const val REQ = 4417 }
}

/** Process-scoped one-shot channel between ConsentActivity and CommandLink. */
object GrantBus {
    @Volatile var latch: CountDownLatch = CountDownLatch(1)
    @Volatile var result: Boolean? = null
    @Volatile var intent: Intent? = null
    @Volatile var resultCode: Int = Int.MIN_VALUE

    fun arm() { latch = CountDownLatch(1); result = null; intent = null; resultCode = Int.MIN_VALUE }
    fun await(timeoutSec: Long): Boolean {
        latch.await(timeoutSec, TimeUnit.SECONDS)
        return result == true
    }
}
