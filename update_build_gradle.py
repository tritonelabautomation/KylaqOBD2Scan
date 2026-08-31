import re

with open('app/build.gradle.kts', 'r') as f:
    content = f.read()

# Create a process execution string for git commit
git_commit_block = """
fun getGitCommitHash(): String {
    return try {
        val proc = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        proc.inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
    } catch (e: Exception) {
        "unknown"
    }
}
val gitCommit = getGitCommitHash()
"""

# add git_commit_block at the very top (after imports)
content = content.replace('plugins {', git_commit_block + '\nplugins {')

# update versionName and versionCode
content = re.sub(r'versionCode = 1\n\s*versionName = "1\.0"', 'versionCode = 100\n    versionName = "1.0.0"', content)

# update buildConfigField in defaultConfig
default_config_pattern = r'(defaultConfig \{.*?)\n  \}'
default_config_replacement = r'\1\n    buildConfigField("String", "GIT_COMMIT", "\\"${gitCommit}\\"")\n  }'
content = re.sub(default_config_pattern, default_config_replacement, content, flags=re.DOTALL)

# add apk renaming for debug and release in android {} block
apk_rename_block = """
  applicationVariants.all {
    val variant = this
    variant.outputs
      .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
      .forEach { output ->
        val outputFileName = "OBDLogger-v${variant.versionName}-build${variant.versionCode}-${variant.name}.apk"
        output.outputFileName = outputFileName
      }
  }
"""

content = content.replace('testOptions {', apk_rename_block + '\n  testOptions {')

with open('app/build.gradle.kts', 'w') as f:
    f.write(content)

