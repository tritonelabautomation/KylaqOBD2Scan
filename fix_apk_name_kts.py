with open('app/build.gradle.kts', 'r') as f:
    content = f.read()

# Remove the broken setProperty
content = content.replace('setProperty("archivesBaseName", "OBDLogger-v1.0.0-build100")', '')

# insert base { archivesName.set("OBDLogger-v1.0.0-build100") }
content += '\nbase { archivesName.set("OBDLogger-v1.0.0-build100") }\n'

with open('app/build.gradle.kts', 'w') as f:
    f.write(content)
