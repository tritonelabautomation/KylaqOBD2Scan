import re

with open('app/build.gradle.kts', 'r') as f:
    content = f.read()

# Remove androidComponents block
content = re.sub(r'androidComponents \{.*?\}\n', '', content, flags=re.DOTALL)

# Add setProperty("archivesBaseName", ...) at the end of the file
content += '\nsetProperty("archivesBaseName", "OBDLogger-v1.0.0-build100")\n'

with open('app/build.gradle.kts', 'w') as f:
    f.write(content)

