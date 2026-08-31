with open('app/build.gradle.kts', 'r') as f:
    content = f.read()

import re

# replace git exec block
pattern = re.compile(r'commandLine\("git", "rev-parse", "--short", "HEAD"\)', re.DOTALL)
replacement = """commandLine("git", "rev-parse", "--short", "HEAD")\n        isIgnoreExitValue = true"""

content = pattern.sub(replacement, content)

with open('app/build.gradle.kts', 'w') as f:
    f.write(content)
