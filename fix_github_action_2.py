import re

with open('.github/workflows/build-apk.yml', 'r') as f:
    content = f.read()

# Add a step to rename the apk before uploading
inject_step = """
      - name: Inject Git Commit
        run: |
          sed -i "s/val gitCommit = \\"unknown\\"/val gitCommit = \\"${GITHUB_SHA::7}\\"/" app/build.gradle.kts
"""

content = content.replace('      - name: Build debug APK', inject_step + '      - name: Build debug APK')

with open('.github/workflows/build-apk.yml', 'w') as f:
    f.write(content)

