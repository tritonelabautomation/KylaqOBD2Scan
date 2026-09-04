import re

with open("app/src/main/java/com/example/ui/screens/AutoScanObdScreen.kt", "r") as f:
    text = f.read()

# Add vinDecodeResult to collectAsState
new_states = """    val connectionState by viewModel.connectionState.collectAsState()
    val vehicleVin by viewModel.vehicleVin.collectAsState()
    val vinDecodeResult by viewModel.vinDecodeResult.collectAsState()"""

text = re.sub(r'    val connectionState by viewModel\.connectionState\.collectAsState\(\)\n    val vehicleVin by viewModel\.vehicleVin\.collectAsState\(\)', new_states, text)

# Replace the "Currently, automated catalogue mapping is in development" block with VinDecodeResult UI
ui_replacement = """                Spacer(modifier = Modifier.height(16.dp))
                
                vinDecodeResult?.let { decode ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                            Text("VIN Decode Result", color = TextSecondaryDark, style = MaterialTheme.typography.labelMedium)
                            Divider(color = DarkBorder, modifier = Modifier.padding(vertical = 8.dp))
                            
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("WMI (Manufacturer)", color = TextSecondaryDark, fontSize = 12.sp)
                                Text("${decode.wmi} - ${decode.manufacturerCandidate ?: "Unknown"}", color = TextPrimaryDark, fontSize = 12.sp)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, modifier2 = Modifier.padding(top = 4.dp)) {
                                Text("VDS", color = TextSecondaryDark, fontSize = 12.sp)
                                Text(decode.vds, color = TextPrimaryDark, fontSize = 12.sp)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, modifier2 = Modifier.padding(top = 4.dp)) {
                                Text("Model Year", color = TextSecondaryDark, fontSize = 12.sp)
                                Text(decode.modelYearCandidate?.toString() ?: "Unknown", color = TextPrimaryDark, fontSize = 12.sp)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, modifier2 = Modifier.padding(top = 4.dp)) {
                                Text("Confidence", color = TextSecondaryDark, fontSize = 12.sp)
                                Text(decode.confidence, color = if (decode.confidence == "LIKELY") CyberCyan else WarningRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            
                            if (decode.candidateVariants.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Candidate Matches (${decode.candidateVariants.size})", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                decode.candidateVariants.take(3).forEach { variant ->
                                    Text("• ${variant.name}", color = TextPrimaryDark, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                                }
                                if (decode.candidateVariants.size > 3) {
                                    Text("...and ${decode.candidateVariants.size - 3} more", color = TextSecondaryDark, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                Button(
                    onClick = onManualSelect,
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = DarkCanvas),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Text(if (vinDecodeResult?.candidateVariants?.isNotEmpty() == true) "Review & Confirm Catalogue Match" else "Select Vehicle Manually")
                }"""

text = re.sub(r'                Spacer\(modifier = Modifier\.height\(32\.dp\)\)\n                Text\(\n                    "Currently, automated catalogue mapping is in development\\n.*?Text\("Continue to Catalogue Selection"\)\n                \}', ui_replacement, text, flags=re.DOTALL)
text = text.replace("modifier2 = Modifier.padding(top = 4.dp)", "modifier = Modifier.padding(top = 4.dp)")

with open("app/src/main/java/com/example/ui/screens/AutoScanObdScreen.kt", "w") as f:
    f.write(text)
