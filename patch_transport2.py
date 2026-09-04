with open("app/src/main/java/com/example/bluetooth/Elm327Transport.kt", "r") as f:
    text = f.read()

import_add = "import kotlinx.coroutines.yield\nimport kotlinx.coroutines.isActive\n"
text = text.replace("import kotlinx.coroutines.sync.withLock", "import kotlinx.coroutines.sync.withLock\n" + import_add)

loop_old = """            while (SystemClock.elapsedRealtime() - startMonotonic < timeoutMs) {
                if (inStream.available() > 0) {
                    val bytesRead = inStream.read(byteBuffer)"""

loop_new = """            while (isActive && SystemClock.elapsedRealtime() - startMonotonic < timeoutMs) {
                if (inStream.available() > 0) {
                    val bytesRead = inStream.read(byteBuffer)"""
text = text.replace(loop_old, loop_new)

sleep_old = """                } else {
                    Thread.sleep(10)
                }"""
sleep_new = """                } else {
                    kotlinx.coroutines.delay(10)
                }"""
text = text.replace(sleep_old, sleep_new)

text = text.replace("import kotlinx.coroutines.withContext\n", "import kotlinx.coroutines.withContext\nimport kotlinx.coroutines.delay\n")

with open("app/src/main/java/com/example/bluetooth/Elm327Transport.kt", "w") as f:
    f.write(text)
