package com.viswa.localbeam.ui.scanner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viswa.localbeam.data.model.DlnaDevice
import com.viswa.localbeam.server.LocalServerState
import com.viswa.localbeam.ui.components.Banner
import com.viswa.localbeam.ui.components.BannerTone
import com.viswa.localbeam.ui.components.BusyRow
import com.viswa.localbeam.ui.components.EmptyState
import com.viswa.localbeam.ui.components.LocalBeamScaffold
import com.viswa.localbeam.ui.components.SectionLabel

/**
 * Screen 1: find the TV.
 *
 * Shows the address the TV will have to dial (the single most common reason casting
 * fails is the phone being on the wrong network) and lists every discovered
 * MediaRenderer with its IP.
 */
@Composable
fun DeviceScannerScreen(
    onDeviceSelected: () -> Unit,
    viewModel: DeviceScannerViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LocalBeamScaffold(title = "LocalBeam", subtitle = "Cast local videos to a DLNA TV") {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LocalNetworkCard(
                addressLabel = state.localAddressLabel,
                isHotspot = state.isHotspot,
                serverState = state.serverState,
            )

            Button(
                onClick = viewModel::scan,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !state.isScanning,
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = if (state.isScanning) "Scanning…" else "Scan for TVs",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (state.isScanning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BusyRow(text = state.message ?: "Searching…", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = viewModel::cancelScan) { Text("Cancel") }
                }
            } else if (!state.message.isNullOrBlank()) {
                Banner(state.message.orEmpty(), BannerTone.Info)
            }

            state.error?.let { Banner(it, BannerTone.Error) }

            if (state.devices.isNotEmpty()) {
                SectionLabel("Found ${state.devices.size} device(s)")
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.devices, key = { it.id }) { device ->
                    DeviceRow(
                        device = device,
                        onClick = {
                            viewModel.selectDevice(device)
                            onDeviceSelected()
                        },
                    )
                }

                if (!state.isScanning && state.devices.isEmpty()) {
                    item {
                        EmptyState(
                            title = if (state.hasScanned) "No TVs found" else "Ready to scan",
                            message = if (state.hasScanned) {
                                "Check that the TV is switched on, connected to the same network as " +
                                    "this phone, and that its UPnP/DLNA setting is enabled. Some TVs " +
                                    "only advertise while on the home screen."
                            } else {
                                "Start a scan to look for DLNA MediaRenderers on your network."
                            },
                        )
                    }
                }

                item { Column(Modifier.height(16.dp)) {} }
            }
        }
    }
}

@Composable
private fun LocalNetworkCard(
    addressLabel: String,
    isHotspot: Boolean,
    serverState: LocalServerState,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "This phone",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = addressLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            when (serverState) {
                is LocalServerState.Ready -> Text(
                    text = "Serving on port ${serverState.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is LocalServerState.Failed -> Text(
                    text = serverState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                LocalServerState.Idle -> Text(
                    text = if (isHotspot) {
                        "Hotspot detected – the TV must be connected to this hotspot."
                    } else {
                        "The TV must be on the same Wi-Fi network."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DlnaDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "TV",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.friendlyName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = device.subtitle ?: "DLNA MediaRenderer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = device.host,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (device.port > 0) {
                    Text(
                        text = "port ${device.port}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
