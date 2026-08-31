with open('app/build.gradle.kts', 'r') as f:
    content = f.read()

import re

# replace git exec block
pattern = re.compile(r'val gitCommit = try \{.*?\}\n', re.DOTALL)
replacement = """val gitCommit = "unknown"\n"""

content = pattern.sub(replacement, content)

with open('app/build.gradle.kts', 'w') as f:
    f.write(content)
