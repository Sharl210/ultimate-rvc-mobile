package com.ultimatervc.mobile

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.EventChannel.StreamHandler
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

class RVCPlugin : FlutterPlugin, MethodCallHandler, StreamHandler {
    private lateinit var channel: MethodChannel
    private lateinit var progressChannel: EventChannel
    private var eventSink: EventSink? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val progressQueue = ConcurrentLinkedQueue<Map<String, Any>>()
    
    private var python: Python? = null
    private var isPythonInitialized = false

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "ultimate_rvc")
        progressChannel = EventChannel(flutterPluginBinding.binaryMessenger, "ultimate_rvc_progress")
        
        channel.setMethodCallHandler(this)
        progressChannel.setStreamHandler(this)
        
        // Initialize Python when plugin is attached
        initializePython(flutterPluginBinding.applicationContext)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        progressChannel.setStreamHandler(null)
        scope.cancel()
    }

    private fun initializePython(context: Context) {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            python = Python.getInstance()
            isPythonInitialized = true
        } catch (e: Exception) {
            isPythonInitialized = false
            sendError("Failed to initialize Python: ${e.message}")
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "initialize" -> initialize(call, result)
            "checkModels" -> checkModels(call, result)
            "downloadModels" -> downloadModels(call, result)
            "infer" -> infer(call, result)
            "getVersion" -> getVersion(call, result)
            else -> result.notImplemented()
        }
    }

    private fun initialize(call: MethodCall, result: Result) {
        if (isPythonInitialized) {
            result.success(true)
        } else {
            result.error("PYTHON_NOT_INITIALIZED", "Python failed to initialize", null)
        }
    }

    private fun checkModels(call: MethodCall, result: Result) {
        scope.launch {
            try {
                val py = python ?: run {
                    result.error("PYTHON_NOT_AVAILABLE", "Python not available", null)
                    return@launch
                }
                
                val downloadWeights = py.getModule("download_weights")
                val modelsDir = getModelsDirectory()
                val status = downloadWeights.callAttr("get_models_status", modelsDir)
                
                // Convert Python dict to Map
                val statusMap = status.asMap()
                val allModelsAvailable = statusMap.values.all { it as Boolean }
                
                withContext(Dispatchers.Main) {
                    result.success(allModelsAvailable)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("CHECK_MODELS_FAILED", e.message, null)
                }
            }
        }
    }

    private fun downloadModels(call: MethodCall, result: Result) {
        scope.launch {
            try {
                val py = python ?: run {
                    result.error("PYTHON_NOT_AVAILABLE", "Python not available", null)
                    return@launch
                }
                
                val downloadWeights = py.getModule("download_weights")
                val modelsDir = getModelsDirectory()
                
                // Start download in background
                launch {
                    downloadWeights.callAttr(
                        "download_models",
                        modelsDir,
                        false, // force_download
                        object : org.python.core.PyObject() {
                            override fun __call__(args: org.python.core.PyObject[]): org.python.core.PyObject {
                                val progress = args[0].asDouble()
                                val status = args[1].asString()
                                
                                val progressMap = mapOf(
                                    "percent" to progress,
                                    "current_step" to status,
                                    "eta" to 0
                                )
                                
                                sendProgress(progressMap)
                                return org.python.core.Py.NONE
                            }
                        }
                    )
                }
                
                withContext(Dispatchers.Main) {
                    result.success(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("DOWNLOAD_FAILED", e.message, null)
                }
            }
        }
    }

    private fun infer(call: MethodCall, result: Result) {
        val songPath = call.argument<String>("songPath")
        val modelPath = call.argument<String>("modelPath")
        val pitchChange = call.argument<Double>("pitchChange") ?: 0.0
        val indexRate = call.argument<Double>("indexRate") ?: 0.75
        val filterRadius = call.argument<Int>("filterRadius") ?: 3
        val rmsMixRate = call.argument<Double>("rmsMixRate") ?: 0.25
        val protectRate = call.argument<Double>("protectRate") ?: 0.33

        if (songPath == null || modelPath == null) {
            result.error("INVALID_ARGUMENTS", "Missing songPath or modelPath", null)
            return
        }

        scope.launch {
            try {
                val py = python ?: run {
                    result.error("PYTHON_NOT_AVAILABLE", "Python not available", null)
                    return@launch
                }
                
                val mainModule = py.getModule("main")
                
                // Start inference in background
                launch {
                    val outputPath = mainModule.callAttr(
                        "infer_rvc",
                        songPath,
                        modelPath,
                        pitchChange.toString(),
                        indexRate.toString(),
                        filterRadius.toString(),
                        rmsMixRate.toString(),
                        protectRate.toString()
                    )
                    
                    sendProgress(mapOf(
                        "percent" to 100.0,
                        "current_step" to "Complete",
                        "eta" to 0
                    ))
                }
                
                withContext(Dispatchers.Main) {
                    result.success(songPath) // Return input path for now
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("INFERENCE_FAILED", e.message, null)
                }
            }
        }
    }

    private fun getVersion(call: MethodCall, result: Result) {
        result.success("1.0.0")
    }

    private fun getModelsDirectory(): String {
        // Return app-specific models directory
        return "/data/data/com.ultimatervc.mobile/files/models"
    }

    private fun sendProgress(progress: Map<String, Any>) {
        eventSink?.success(progress)
    }

    private fun sendError(error: String) {
        eventSink?.error("PYTHON_ERROR", error, null)
    }

    override fun onListen(arguments: Any?, events: EventSink?) {
        eventSink = events
        
        // Start progress monitoring
        scope.launch {
            while (eventSink != null) {
                try {
                    val py = python
                    if (py != null) {
                        val mainModule = py.getModule("main")
                        val progress = mainModule.callAttr("get_progress")
                        
                        val progressMap = mapOf(
                            "percent" to progress["percent"],
                            "current_step" to progress["current_step"],
                            "eta" to progress["eta"]
                        )
                        
                        sendProgress(progressMap)
                    }
                } catch (e: Exception) {
                    // Ignore errors during progress monitoring
                }
                
                delay(1000) // Check every second
            }
        }
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }
}