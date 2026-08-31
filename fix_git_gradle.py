with open('app/build.gradle.kts', 'r') as f:
    content = f.read()

import re

# replace getGitCommitHash block
pattern = re.compile(r'fun getGitCommitHash\(\): String \{.*?val gitCommit = getGitCommitHash\(\)\n', re.DOTALL)
replacement = """
val gitCommit = try {
    providers.exec { 
        commandLine("git", "rev-parse", "--short", "HEAD") 
    }.standardOutput.asText.get().trim()
} catch(e: Exception) {
    "unknown"
}
"""

content = pattern.sub(replacement, content)

with open('app/build.gradle.kts', 'w') as f:
    f.write(content)
