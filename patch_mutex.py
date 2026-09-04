with open("app/src/main/java/com/example/bluetooth/Elm327Transport.kt", "r") as f:
    text = f.read()

text = text.replace("import kotlinx.coroutines.withContext", "import kotlinx.coroutines.withContext\nimport kotlinx.coroutines.sync.Mutex\nimport kotlinx.coroutines.sync.withLock")

class_start = """class BluetoothElmTransport(
    private val socket: BluetoothSocket
) : ElmTransport {"""
text = text.replace(class_start, class_start + "\n    private val transportMutex = Mutex()")

send_cmd_sig = "override suspend fun sendCommand(command: String, timeoutMs: Long): ElmResponse = withContext(Dispatchers.IO) {"
send_cmd_body = "override suspend fun sendCommand(command: String, timeoutMs: Long): ElmResponse = withContext(Dispatchers.IO) {\n        transportMutex.withLock {"
text = text.replace(send_cmd_sig, send_cmd_body)

send_cmd_end = """        } catch (e: Exception) {
            val duration = SystemClock.elapsedRealtime() - startMonotonic
            logRaw(isTx = false, canId = null, text = "IO Error: ${e.localizedMessage}", status = "ERROR")
            return@withContext ElmResponse(
                rawText = "",
                lines = emptyList(),
                status = com.example.model.ResponseStatus.CAN_ERROR,
                isPromptReceived = false,
                durationMs = duration,
                errorMessage = e.localizedMessage
            )
        }
    }"""
send_cmd_end_new = """        } catch (e: Exception) {
            val duration = SystemClock.elapsedRealtime() - startMonotonic
            logRaw(isTx = false, canId = null, text = "IO Error: ${e.localizedMessage}", status = "ERROR")
            return@withContext ElmResponse(
                rawText = "",
                lines = emptyList(),
                status = com.example.model.ResponseStatus.CAN_ERROR,
                isPromptReceived = false,
                durationMs = duration,
                errorMessage = e.localizedMessage
            )
        }
        }
    }"""
text = text.replace(send_cmd_end, send_cmd_end_new)

init_cmd = """    override suspend fun initializeAdapter(initSequence: List<String>): List<Pair<String, ElmResponse>> {
        val results = mutableListOf<Pair<String, ElmResponse>>()"""

with open("app/src/main/java/com/example/bluetooth/Elm327Transport.kt", "w") as f:
    f.write(text)
