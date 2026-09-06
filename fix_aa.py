#!/usr/bin/env python3
# Fix 1: Revert AndroidManifest.xml - NAVIGATION and minCarApiLevel
manifest_content = '''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" tools:targetApi="s" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <uses-feature android:name="android.hardware.bluetooth" android:required="false" />

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.MyApplication">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"
            android:theme="@style/Theme.MyApplication">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".auto.ObdCarAppService"
            android:icon="@mipmap/ic_launcher"
            android:label="OBD Logger"
            android:exported="true">
            <intent-filter>
                <action android:name="androidx.car.app.CarAppService" />
                <category android:name="androidx.car.app.category.IOT" />
            </intent-filter>
        </service>

        <meta-data
            android:name="androidx.car.app.minCarApiLevel"
            android:value="1" />
            
        <meta-data
            android:name="com.google.android.gms.car.application"
            android:resource="@xml/automotive_app_desc" />

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>

</manifest>
'''

with open('app/src/main/AndroidManifest.xml', 'w', encoding='utf-8') as f:
    f.write(manifest_content)
print('Fixed AndroidManifest.xml: reverted NAVIGATION, minCarApiLevel=1')

# Fix 2: Fix isInitialized bug in ObdDashboardScreen.kt
with open('app/src/main/java/com/example/auto/ObdDashboardScreen.kt', 'r', encoding='utf-8') as f:
    screen_content = f.read()

old_init = '''    private fun initializeOnce() {
        if (isInitialized) return
        isInitialized = true
        
        try {
            AppContainer.init(carContext.applicationContext)
            subscribeToTelemetry()
        } catch (t: Throwable) {
            Log.e("OBDLogger/AndroidAuto", "Failed to initialize AppContainer or subscribe to telemetry: ${t.message}", t)
        }
    }'''

new_init = '''    private fun initializeOnce() {
        if (isInitialized) return
        
        try {
            AppContainer.init(carContext.applicationContext)
            subscribeToTelemetry()
            isInitialized = true  // Set AFTER successful initialization
            Log.i("OBDLogger/AndroidAuto", "initializeOnce: success")
        } catch (t: Throwable) {
            // Do NOT set isInitialized = true on failure - allow retry
            Log.e("OBDLogger/AndroidAuto", "initializeOnce: failed - will retry on next onCreate: ${t.message}", t)
        }
    }'''

screen_content = screen_content.replace(old_init, new_init)

with open('app/src/main/java/com/example/auto/ObdDashboardScreen.kt', 'w', encoding='utf-8') as f:
    f.write(screen_content)
print('Fixed ObdDashboardScreen.kt: isInitialized now set AFTER success')
