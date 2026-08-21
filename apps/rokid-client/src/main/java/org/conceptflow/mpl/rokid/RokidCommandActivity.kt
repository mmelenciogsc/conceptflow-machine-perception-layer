// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.rokid

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import org.conceptflow.mpl.rokid.core.RuntimeCommand

class RokidCommandActivity : Activity(), android.content.ServiceConnection {
    private var runtime: RokidRuntimeService.RuntimeBinder? = null
    private var bound = false
    private var pendingCommand: RuntimeCommand? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pendingCommand = RuntimeCommand.fromAction(intent?.action)
        if (pendingCommand == null) {
            Log.w(TAG, "state=rejected reason=unknown_command")
            finishAndRemoveTask()
            return
        }
        val connected = bindService(
            Intent(this, RokidRuntimeService::class.java),
            this,
            Context.BIND_AUTO_CREATE,
        )
        if (!connected) {
            Log.e(TAG, "state=rejected reason=service_bind_failed")
            finishAndRemoveTask()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val command = RuntimeCommand.fromAction(intent.action)
        if (command == null) {
            Log.w(TAG, "state=rejected reason=unknown_command")
            return
        }
        pendingCommand = command
        runtime?.let { dispatch(it, command) }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val binder = service as? RokidRuntimeService.RuntimeBinder
        if (binder == null) {
            Log.e(TAG, "state=rejected reason=unexpected_service_binder")
            finishAndRemoveTask()
            return
        }
        runtime = binder
        bound = true
        pendingCommand?.let { dispatch(binder, it) }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        runtime = null
        bound = false
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(this)
            bound = false
        }
        runtime = null
        super.onDestroy()
    }

    private fun dispatch(binder: RokidRuntimeService.RuntimeBinder, command: RuntimeCommand) {
        pendingCommand = null
        binder.execute(command) { runOnUiThread(::finishAndRemoveTask) }
    }

    companion object {
        private const val TAG = "ConceptFlowRokid"
    }
}
