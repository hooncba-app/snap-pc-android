package com.snapandsort.pcbridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class SnapPcAndroidPlugin : FlutterPlugin, ActivityAware,
    MethodChannel.MethodCallHandler, PluginRegistry.ActivityResultListener {
    private data class Request(val result: MethodChannel.Result, val limit: Long)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var context: Context
    private lateinit var channel: MethodChannel
    private var activity: ActivityPluginBinding? = null
    private var pending: Request? = null
    private var reading = false
    private var attached = false

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "snap_and_sort/pc_flutterflow_v1")
        channel.setMethodCallHandler(this)
        attached = true
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        attached = false
        channel.setMethodCallHandler(null)
        cancelPicker()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding
        binding.addActivityResultListener(this)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) =
        onAttachedToActivity(binding)

    override fun onDetachedFromActivityForConfigChanges() = detachActivity()

    override fun onDetachedFromActivity() {
        detachActivity()
        cancelPicker()
    }

    private fun detachActivity() {
        activity?.removeActivityResultListener(this)
        activity = null
    }

    private fun cancelPicker() {
        pending?.result?.error("PICK_CANCELLED", "파일 선택이 중단됐습니다.", null)
        pending = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "wifiActive" -> {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                val active = manager.activeNetwork
                result.success(active != null && manager.getNetworkCapabilities(active)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
            }
            "pickFile" -> pick(call, result)
            "originalProof" -> SnapPcOriginalHash.read(context.contentResolver, call, result)
            else -> result.notImplemented()
        }
    }

    private fun pick(call: MethodCall, result: MethodChannel.Result) {
        val screen = activity?.activity
        val limit = call.argument<Number>("limit")?.toLong()
        if (screen == null || pending != null || reading) {
            result.error("PICK_BUSY", "파일 선택 화면을 열 수 없습니다. 이전 작업이 끝난 뒤 다시 시도하세요.", null)
            return
        }
        if (limit == null || limit !in 1..168296462L) {
            result.error("PICK_LIMIT", "파일 크기 한도가 올바르지 않습니다.", null)
            return
        }
        pending = Request(result, limit)
        try {
            screen.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, REQUEST_CODE)
        } catch (_: RuntimeException) {
            pending = null
            result.error("PICK_FAILED", "Android 파일 선택기를 열지 못했습니다.", null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CODE) return false
        val request = pending ?: return false
        pending = null
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            request.result.success(null)
            return true
        }
        reading = true
        io.execute {
            var target: File? = null
            try {
                val file = File.createTempFile("snap-pc-picker-", ".part", context.cacheDir)
                target = file
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("선택한 파일을 읽을 수 없습니다.")
                input.use { stream ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var size = 0L
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            size += count
                            if (size > request.limit) throw IOException("선택 파일이 크기 한도를 초과합니다.")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                main.post {
                    reading = false
                    if (attached) request.result.success(file.absolutePath) else file.delete()
                }
            } catch (error: Exception) {
                target?.delete()
                main.post {
                    reading = false
                    if (attached) request.result.error("READ_FAILED",
                        if (error is IOException && error.message == "선택 파일이 크기 한도를 초과합니다.")
                            error.message else "선택 파일 또는 저장 공간을 확인하세요.", null)
                }
            }
        }
        return true
    }

    companion object {
        private const val REQUEST_CODE = 4831
    }
}
