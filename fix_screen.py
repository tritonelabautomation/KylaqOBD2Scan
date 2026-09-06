#!/usr/bin/env python3
import re

with open('app/src/main/java/com/example/auto/ObdDashboardScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Add isInitialized flag after class declaration
content = content.replace(
    'class ObdDashboardScreen(carContext: CarContext) : Screen(carContext) {',
    '''class ObdDashboardScreen(carContext: CarContext) : Screen(carContext) {
    
    private var isInitialized = false'''
)

# Fix onCreate to call initializeOnce
content = content.replace(
    '''            override fun onCreate(owner: LifecycleOwner) {
                Log.i("OBDLogger/AndroidAuto", "ObdDashboardScreen lifecycle onCreate")
            }''',
    '''            override fun onCreate(owner: LifecycleOwner) {
                Log.i("OBDLogger/AndroidAuto", "ObdDashboardScreen lifecycle onCreate")
                initializeOnce()
            }'''
)

# Remove AppContainer.init from onGetTemplate and the isSubscribed check
old_template_start = '''    override fun onGetTemplate(): Template {
        try {
            Log.i("OBDLogger/AndroidAuto", "onGetTemplate called, connection: $connectionState, protocol: $protocolHealth")
            
            // AppContainer initialization in try-catch so it never blocks or prevents template return
            try {
                AppContainer.init(carContext.applicationContext)
                if (!isSubscribed) {
                    isSubscribed = true
                    subscribeToTelemetry()
                }
            } catch (t: Throwable) {
                Log.e("OBDLogger/AndroidAuto", "Failed to initialize AppContainer or subscribe to telemetry: ${t.message}", t)
            }

            val paneBuilder = Pane.Builder()'''

new_template_start = '''    override fun onGetTemplate(): Template {
        try {
            Log.i("OBDLogger/AndroidAuto", "onGetTemplate called, connection: $connectionState, protocol: $protocolHealth")

            val paneBuilder = Pane.Builder()'''

content = content.replace(old_template_start, new_template_start)

# Remove isSubscribed variable
content = content.replace(
    '''    private var isSubscribed = false
''',
    ''
)

# Add initializeOnce function before subscribeToTelemetry
content = content.replace(
    '''    private fun subscribeToTelemetry() {
        try {''',
    '''    private fun initializeOnce() {
        if (isInitialized) return
        isInitialized = true
        
        try {
            AppContainer.init(carContext.applicationContext)
            subscribeToTelemetry()
        } catch (t: Throwable) {
            Log.e("OBDLogger/AndroidAuto", "Failed to initialize AppContainer or subscribe to telemetry: ${t.message}", t)
        }
    }

    private fun subscribeToTelemetry() {
        try {'''
)

with open('app/src/main/java/com/example/auto/ObdDashboardScreen.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print('ObdDashboardScreen.kt updated successfully')
