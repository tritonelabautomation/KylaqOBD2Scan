#!/usr/bin/env python3
import sys

filepath = 'c:/KylaqOBD2Scan/app/src/main/java/com/example/scheduler/ObdScheduler.kt'

with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

old_text = '''        // 4. Decode each reassembled response (prioritizing expected ECU e.g. 7E8)
        // FIX P0-7: Expected ECU must sort FIRST (0 = lowest sort key = processed first in ascending sort).
        // Previously "1 else 0" incorrectly placed expected ECU AFTER other ECUs, allowing other
        // ECUs' responses to be decoded first and contaminate the per-PID validated ECU state.
        val sortedMessages = isoTpMessages.sortedBy { msg ->
            if (msg.canId.equals(pidDef.expectedRxId, ignoreCase = true) || msg.canId.equals("7E8", ignoreCase = true)) 0 else 1
        }'''

new_text = '''        // 4. Decode each reassembled response (prioritizing evidence-based preferred ECU)
        // FIX P0-2: Sort by evidence-based ECU selection, not hardcoded 7E8.
        // The preferred ECU is now determined by the capability manager based on actual
        // discovery evidence (engine > transmission > alphabetical), not a global default.
        val preferredEcu = capabilityManager.getPreferredEcuForPid(pidDef.id)
        val sortedMessages = isoTpMessages.sortedBy { msg ->
            val msgEcu = msg.canId?.uppercase() ?: ""
            // Priority 1: Evidence-based preferred ECU (from capability discovery)
            // Priority 2: Canonical engine ECU 7E8 (MQB standard)
            // Priority 3: Any other responding ECU
            when {
                preferredEcu != null && msgEcu == preferredEcu.uppercase() -> 0
                msgEcu == "7E8" -> 1
                else -> 2
            }
        }'''

if old_text in content:
    content = content.replace(old_text, new_text)
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)
    print('SUCCESS: File updated')
else:
    print('ERROR: Old text not found')
    sys.exit(1)