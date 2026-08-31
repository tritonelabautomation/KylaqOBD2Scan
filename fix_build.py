import re

with open('app/build.gradle.kts', 'r') as f:
    content = f.read()

# fix TimeUnit
content = content.replace('java.util.concurrent.TimeUnit.SECONDS', 'java.util.concurrent.TimeUnit.SECONDS')
content = content.replace('proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)', 'proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)')

# Wait, the error is `Unresolved reference 'util'`. That's because it's in `java.util...`. Let's just use 
# proc.waitFor(5000, java.util.concurrent.TimeUnit.MILLISECONDS) No, I'll just use Thread.sleep or an explicit import.
# Let's import it. Or I can just remove the wait and let it finish (rev-parse is instant).
# Let's just remove the `waitFor` with TimeUnit, and use proc.waitFor()
content = content.replace('proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)', 'proc.waitFor()')

# Fix applicationVariants block. In Kotlin DSL, to rename APK we should use androidComponents or just keep it simple.
# Wait, `applicationVariants` requires `android.applicationVariants`, but it's not directly accessible in KTS without casting or using specific blocks.
# Let's use androidComponents instead.
android_components_block = """
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val name = variant.name
            output.outputFileName.set("OBDLogger-v1.0.0-build100-${name}.apk")
        }
    }
}
"""

# Remove the broken applicationVariants.all { ... } block
content = re.sub(r'  applicationVariants\.all \{.*?\n  \}\n', '', content, flags=re.DOTALL)
content = content.replace('android {', android_components_block + '\nandroid {')

with open('app/build.gradle.kts', 'w') as f:
    f.write(content)

