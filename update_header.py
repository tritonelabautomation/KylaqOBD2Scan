import re

with open('app/src/main/java/com/example/ui/screens/DashboardScreen.kt', 'r') as f:
    content = f.read()

new_header = """@Composable
fun VehicleStatusHeader(
    vehicleName: String,
    adapterName: String,
    connectionState: ConnectionState,
    protocol: String,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = vehicleName.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = adapterName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                if (connectionState == ConnectionState.CONNECTED) {
                    OutlinedButton(
                        onClick = onDisconnectClick,
                        border = border.BorderStroke(1.dp, WarningRed),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = WarningRed)
                    ) {
                        Text("Disconnect", fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = onConnectClick,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
                    ) {
                        Text(if (connectionState == ConnectionState.CONNECTING) "Connecting..." else "Connect", fontSize = 12.sp)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusBadge(label = "Bluetooth", isActive = connectionState == ConnectionState.CONNECTED)
                StatusBadge(label = "Adapter", isActive = connectionState == ConnectionState.CONNECTED)
                StatusBadge(label = "CAN", isActive = connectionState == ConnectionState.CONNECTED)
                StatusBadge(label = "ECU", isActive = connectionState == ConnectionState.CONNECTED)
            }
        }
    }
}
"""

pattern = re.compile(r'@Composable\nfun VehicleStatusHeader\(.*?}\n}\n', re.DOTALL)
content = pattern.sub(new_header, content)

with open('app/src/main/java/com/example/ui/screens/DashboardScreen.kt', 'w') as f:
    f.write(content)

