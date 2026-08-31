import re

with open('.github/workflows/build-apk.yml', 'r') as f:
    content = f.read()

# Add a step to rename the apk before uploading
rename_step = """
      - name: Rename APK
        run: |
          mv app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/debug/OBDLogger-v1.0.0-build100-debug.apk || true
"""

content = content.replace('      - name: Upload debug APK', rename_step + '      - name: Upload debug APK')

with open('.github/workflows/build-apk.yml', 'w') as f:
    f.write(content)

