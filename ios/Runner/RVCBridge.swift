import Foundation
import Flutter
import PythonKit

class RVCBridge {
    private var python: PythonObject?
    private var mainModule: PythonObject?
    private var downloadWeights: PythonObject?
    
    init() {
        initializePython()
    }
    
    private func initializePython() {
        do {
            // Initialize Python environment
            let sys = Python.import("sys")
            
            // Add Python path for our modules
            sys.path.append("../../python")
            
            // Import main modules
            mainModule = Python.import("main")
            downloadWeights = Python.import("download_weights")
            
            print("Python initialized successfully")
        } catch {
            print("Failed to initialize Python: \(error)")
        }
    }
    
    func checkModels() -> Bool {
        guard let downloadWeights = downloadWeights else {
            print("Download weights module not available")
            return false
        }
        
        do {
            let modelsDir = getModelsDirectory()
            let status = downloadWeights.get_models_status(modelsDir)
            
            // Convert Python dict to Swift Dictionary
            let statusDict = Dictionary<String, Bool>(uniqueKeysWithValues: 
                status.items().map { ($0.key as? String ?? "", $0.value as? Bool ?? false) }
            )
            
            let allAvailable = statusDict.values.allSatisfy { $0 }
            print("Models status: \(statusDict), all available: \(allAvailable)")
            
            return allAvailable
        } catch {
            print("Failed to check models: \(error)")
            return false
        }
    }
    
    func downloadModels(onProgress: @escaping (Double, String) -> Void) {
        guard let downloadWeights = downloadWeights else {
            print("Download weights module not available")
            return
        }
        
        DispatchQueue.global(qos: .background).async {
            do {
                let modelsDir = self.getModelsDirectory()
                
                // Create progress callback
                let progressCallback = PythonObject { (args: [PythonObject]) -> PythonObject in
                    if args.count >= 2 {
                        let progress = Double(args[0]) ?? 0.0
                        let status = String(args[1]) ?? ""
                        
                        DispatchQueue.main.async {
                            onProgress(progress, status)
                        }
                    }
                    return Python.None
                }
                
                // Start download
                downloadWeights.download_models(modelsDir, false, progressCallback)
                
                DispatchQueue.main.async {
                    onProgress(100.0, "Download complete")
                }
                
            } catch {
                print("Failed to download models: \(error)")
                DispatchQueue.main.async {
                    onProgress(0.0, "Download failed: \(error)")
                }
            }
        }
    }
    
    func infer(
        songPath: String,
        modelPath: String,
        pitchChange: Double = 0.0,
        indexRate: Double = 0.75,
        filterRadius: Int = 3,
        rmsMixRate: Double = 0.25,
        protectRate: Double = 0.33,
        onProgress: @escaping (Double, String) -> Void,
        completion: @escaping (String?) -> Void
    ) {
        guard let mainModule = mainModule else {
            print("Main module not available")
            completion(nil)
            return
        }
        
        DispatchQueue.global(qos: .background).async {
            do {
                // Start inference
                let outputPath = mainModule.infer_rvc(
                    songPath,
                    modelPath,
                    String(pitchChange),
                    String(indexRate),
                    String(filterRadius),
                    String(rmsMixRate),
                    String(protectRate)
                )
                
                let result = String(outputPath) ?? ""
                
                DispatchQueue.main.async {
                    completion(result)
                }
                
            } catch {
                print("Inference failed: \(error)")
                DispatchQueue.main.async {
                    completion(nil)
                }
            }
        }
    }
    
    func getProgress() -> [String: Any] {
        guard let mainModule = mainModule else {
            return ["percent": 0, "current_step": "Not initialized", "eta": 0]
        }
        
        do {
            let progress = mainModule.get_progress()
            
            return [
                "percent": Double(progress["percent"]) ?? 0.0,
                "current_step": String(progress["current_step"]) ?? "Unknown",
                "eta": Int(progress["eta"]) ?? 0
            ]
        } catch {
            print("Failed to get progress: \(error)")
            return ["percent": 0, "current_step": "Error", "eta": 0]
        }
    }
    
    private func getModelsDirectory() -> String {
        // Get app-specific documents directory
        let paths = FileManager.default.urls(for: .documentDirectory, 
                                           in: .userDomainMask)
        let documentsDirectory = paths[0]
        let modelsDirectory = documentsDirectory.appendingPathComponent("models")
        
        // Create directory if it doesn't exist
        try? FileManager.default.createDirectory(at: modelsDirectory,
                                                withIntermediateDirectories: true,
                                                attributes: nil)
        
        return modelsDirectory.path
    }
}

// Flutter plugin implementation
class RVCPluginiOS: NSObject, FlutterPlugin {
    private static let channelName = "ultimate_rvc"
    private static let progressChannelName = "ultimate_rvc_progress"
    
    private let rvcBridge = RVCBridge()
    private var progressTimer: Timer?
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: channelName, binaryMessenger: registrar.messenger())
        let instance = RVCPluginiOS()
        registrar.addMethodCallDelegate(instance, channel: channel)
        
        // Register progress event channel
        let progressChannel = FlutterEventChannel(name: progressChannelName, binaryMessenger: registrar.messenger())
        progressChannel.setStreamHandler(instance)
    }
    
    func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "initialize":
            result(true)
            
        case "checkModels":
            let modelsAvailable = rvcBridge.checkModels()
            result(modelsAvailable)
            
        case "downloadModels":
            rvcBridge.downloadModels { progress, status in
                // Send progress through event channel
                self.sendProgressEvent(percent: progress, status: status)
            }
            result(true)
            
        case "infer":
            guard let args = call.arguments as? [String: Any],
                  let songPath = args["songPath"] as? String,
                  let modelPath = args["modelPath"] as? String else {
                result(FlutterError(code: "INVALID_ARGUMENTS", message: "Missing songPath or modelPath", details: nil))
                return
            }
            
            let pitchChange = args["pitchChange"] as? Double ?? 0.0
            let indexRate = args["indexRate"] as? Double ?? 0.75
            let filterRadius = args["filterRadius"] as? Int ?? 3
            let rmsMixRate = args["rmsMixRate"] as? Double ?? 0.25
            let protectRate = args["protectRate"] as? Double ?? 0.33
            
            rvcBridge.infer(
                songPath: songPath,
                modelPath: modelPath,
                pitchChange: pitchChange,
                indexRate: indexRate,
                filterRadius: filterRadius,
                rmsMixRate: rmsMixRate,
                protectRate: protectRate,
                onProgress: { progress, status in
                    self.sendProgressEvent(percent: progress, status: status)
                },
                completion: { outputPath in
                    if let outputPath = outputPath {
                        result(outputPath)
                    } else {
                        result(FlutterError(code: "INFERENCE_FAILED", message: "Inference failed", details: nil))
                    }
                }
            )
            
        case "getVersion":
            result("1.0.0")
            
        default:
            result(FlutterMethodNotImplemented)
        }
    }
    
    private func sendProgressEvent(percent: Double, status: String) {
        // This would be called by the event channel handler
        // Implementation depends on how events are sent to Flutter
    }
}

// Event channel handler
extension RVCPluginiOS: FlutterStreamHandler {
    func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        // Start progress monitoring
        progressTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { _ in
            let progress = self.rvcBridge.getProgress()
            events(progress)
        }
        
        return nil
    }
    
    func onCancel(withArguments arguments: Any?) -> FlutterError? {
        progressTimer?.invalidate()
        progressTimer = nil
        return nil
    }
}