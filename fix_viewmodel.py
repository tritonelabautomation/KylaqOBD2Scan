import re

with open('app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt', 'r') as f:
    content = f.read()

def replace_pid_loop(text):
    return re.sub(
        r'for \(pid in pidsToTest\) \{.*?kotlinx\.coroutines\.delay\(100\)\n                \}',
        """for (pid in pidsToTest) {
                    val resp = transport.sendCommand(pid, 2000L)
                    val duration = resp.durationMs
                    
                    if (duration > 0) {
                        totalTime += duration
                        if (duration < minTime) minTime = duration
                        if (duration > maxTime) maxTime = duration
                    }
                    
                    val expectedService = "41"
                    val expectedPid = pid.substring(2)
                    
                    val hasEcuResponse = resp.lines.any { line -> 
                        val cleanLine = line.replace(" ", "").uppercase()
                        cleanLine.contains(expectedService + expectedPid)
                    }
                    
                    if (hasEcuResponse) {
                        success++
                    } else if (resp.status == com.example.model.ResponseStatus.TIMEOUT) {
                        timeout++
                    } else if (resp.status == com.example.model.ResponseStatus.NO_DATA) {
                        unsupported++
                    } else {
                        invalid++
                    }
                    kotlinx.coroutines.delay(100)
                }""",
        text,
        flags=re.DOTALL
    )

new_content = replace_pid_loop(content)

with open('app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt', 'w') as f:
    f.write(new_content)

