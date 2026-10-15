package com.valeriinov.flutter_gallery

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.io.FileNotFoundException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FlutterGalleryPlugin :
    FlutterPlugin,
    ActivityAware,
    MethodCallHandler,
    PluginRegistry.ActivityResultListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pickContract = ActivityResultContracts.PickMultipleVisualMedia()
    private lateinit var channel: MethodChannel
    private lateinit var store: ImageStore
    private lateinit var reader: ImageReader
    private lateinit var listExecutor: ExecutorService
    private lateinit var readExecutor: ExecutorService
    private var activityBinding: ActivityPluginBinding? = null
    private var pendingPick: Result? = null
    private var preselected: List<String> = emptyList()
    private var isPickerOpen = false

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        store = ImageStore(binding.applicationContext)
        reader = ImageReader(binding.applicationContext)
        listExecutor = Executors.newSingleThreadExecutor()
        readExecutor = Executors.newFixedThreadPool(READ_THREADS)
        channel = MethodChannel(binding.binaryMessenger, CHANNEL_NAME)
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        listExecutor.shutdown()
        readExecutor.shutdown()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        attachActivity(binding)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        attachActivity(binding)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        detachActivity()
    }

    override fun onDetachedFromActivity() {
        detachActivity()
        pendingPick?.error(NO_ACTIVITY, "The activity was destroyed while the picker was open", null)
        pendingPick = null
        preselected = emptyList()
        isPickerOpen = false
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "pickImages" -> pickImages(result)
            "loadImages" -> runAndReply(listExecutor, result) {
                store.load(call.requireArgument("offset"), call.requireArgument("limit"))
            }
            "hasImage" -> runAndReply(listExecutor, result) { store.has(call.requireArgument("uri")) }
            "saveImage" -> runAndReply(listExecutor, result) {
                store.save(call.requireArgument("path"), call.requireArgument("fileName"))
            }
            "keepImage" -> runAndReply(listExecutor, result) { store.keep(call.requireArgument("path")) }
            "readThumbnail" -> runAndReply(readExecutor, result) {
                reader.readThumbnail(call.requireArgument("uri"), call.requireArgument("size"))
            }
            "readOriginal" -> runAndReply(readExecutor, result) { reader.readOriginal(call.requireArgument("uri")) }
            "clearCache" -> runAndReply(readExecutor, result) {
                reader.clearCache()
                null
            }
            else -> result.notImplemented()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != PICK_REQUEST_CODE) {
            return false
        }
        isPickerOpen = false
        val result = pendingPick
        pendingPick = null
        val picked = pickContract.parseResult(resultCode, data)
        val deselected = findDeselected(resultCode, picked)
        preselected = emptyList()
        runAndReply(listExecutor, result) { store.addPicked(picked, deselected) }

        return true
    }

    private fun attachActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        binding.addActivityResultListener(this)
    }

    private fun detachActivity() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
    }

    private fun pickImages(result: Result) {
        if (isPickerOpen) {
            result.error(PICKER_OPEN, "The picker is already open", null)
            return
        }
        val activity = activityBinding?.activity
        if (activity == null) {
            result.error(NO_ACTIVITY, "The plugin has no foreground activity", null)
            return
        }
        val request = PickVisualMediaRequest.Builder()
            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
            .build()
        val intent = pickContract.createIntent(activity, request)
        val preselection = if (canPreselect(intent)) {
            store.findPreselectable(intent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 0))
        } else {
            emptyList()
        }
        if (preselection.isNotEmpty()) {
            intent.putParcelableArrayListExtra(MediaStore.EXTRA_PICKER_PRE_SELECTION_URIS, ArrayList(preselection))
        }
        try {
            activity.startActivityForResult(intent, PICK_REQUEST_CODE)
        } catch (e: ActivityNotFoundException) {
            result.error(IO_ERROR, e.message, null)
            return
        }
        isPickerOpen = true
        pendingPick = result
        preselected = preselection.map { it.toString() }
    }

    /** Only the platform picker on Android 16 or R extensions 15 shows URIs as selected. */
    private fun canPreselect(intent: Intent): Boolean {
        if (intent.action != MediaStore.ACTION_PICK_IMAGES) {
            return false
        }
        if (Build.VERSION.SDK_INT >= PRESELECTION_SDK) {
            return true
        }

        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= PRESELECTION_R_EXTENSION
    }

    /** A cancelled picker returns no URIs and deselects nothing. */
    private fun findDeselected(resultCode: Int, picked: List<Uri>): List<String> {
        if (resultCode != Activity.RESULT_OK) {
            return emptyList()
        }
        val pickedUris = picked.map { it.toString() }.toSet()

        return preselected.filter { it !in pickedUris }
    }

    private fun runAndReply(executor: ExecutorService, result: Result?, block: () -> Any?) {
        executor.execute {
            try {
                val value = block()
                mainHandler.post { result?.success(value) }
            } catch (e: Exception) {
                val code = errorCode(e)
                mainHandler.post { result?.error(code, e.message, null) }
            }
        }
    }

    private fun errorCode(e: Exception): String = when (e) {
        is FileNotFoundException, is SecurityException -> NOT_FOUND
        else -> IO_ERROR
    }

    private fun <T> MethodCall.requireArgument(name: String): T =
        requireNotNull(argument<T>(name)) { "Missing argument: $name" }

    companion object {
        private const val CHANNEL_NAME = "flutter_gallery"
        private const val PICK_REQUEST_CODE = 0x4647
        private const val READ_THREADS = 4
        private const val PRESELECTION_SDK = 36
        private const val PRESELECTION_R_EXTENSION = 15
        private const val PICKER_OPEN = "picker_open"
        private const val NO_ACTIVITY = "no_activity"
        private const val NOT_FOUND = "not_found"
        private const val IO_ERROR = "io_error"
    }
}
