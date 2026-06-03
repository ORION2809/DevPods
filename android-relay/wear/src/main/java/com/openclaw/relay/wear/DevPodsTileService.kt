package com.openclaw.relay.wear

import android.net.Uri
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Wearable
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TILE_VERSION = "1"
private const val READY_MARK_RESOURCE = "devpods_ready_mark"
private const val APPROVAL_MARK_RESOURCE = "devpods_approval_mark"

class DevPodsTileService : TileService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        return CallbackToFutureAdapter.getFuture { completer ->
            serviceScope.launch {
                try {
                    val state = readWearState()
                    val tile = TileBuilders.Tile.Builder()
                        .setResourcesVersion(TILE_VERSION)
                        .setTileTimeline(
                            TimelineBuilders.Timeline.Builder()
                                .addTimelineEntry(
                                    TimelineBuilders.TimelineEntry.Builder()
                                        .setLayout(
                                            LayoutElementBuilders.Layout.Builder()
                                                .setRoot(buildTileLayout(state))
                                                .build()
                                        )
                                        .build()
                                )
                                .build()
                        )
                        .setFreshnessIntervalMillis(
                            when (state.phase) {
                                "approval_pending" -> 1000L
                                else -> 30_000L
                            }
                        )
                        .build()
                    completer.set(tile)
                } catch (e: Throwable) {
                    completer.setException(e)
                }
            }
        }
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return CallbackToFutureAdapter.getFuture { completer ->
            completer.set(
                ResourceBuilders.Resources.Builder()
                    .setVersion(TILE_VERSION)
                    .addIdToImageMapping(
                        READY_MARK_RESOURCE,
                        ResourceBuilders.ImageResource.Builder()
                            .setAndroidResourceByResId(
                                ResourceBuilders.AndroidImageResourceByResId.Builder()
                                    .setResourceId(R.drawable.devpods_mark_teal_dark)
                                    .build()
                            )
                            .build()
                    )
                    .addIdToImageMapping(
                        APPROVAL_MARK_RESOURCE,
                        ResourceBuilders.ImageResource.Builder()
                            .setAndroidResourceByResId(
                                ResourceBuilders.AndroidImageResourceByResId.Builder()
                                    .setResourceId(R.drawable.devpods_mark_white_mono_transparent)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
        }
    }

    private suspend fun readWearState(): WearState {
        return try {
            val dataClient = Wearable.getDataClient(this)
            val dataItems = dataClient.getDataItems(
                Uri.parse("wear:/devpods/state"),
                DataClient.FILTER_PREFIX
            ).await()

            val item = dataItems.firstOrNull()
            if (item != null) {
                val dataMap = com.google.android.gms.wearable.DataMapItem.fromDataItem(item).dataMap
                WearState(
                    phase = dataMap.getString("phase", "idle"),
                    summary = dataMap.getString("summary", ""),
                    actionId = dataMap.getString("actionId", ""),
                    expiryMs = dataMap.getLong("expiryMs", 0),
                )
            } else {
                WearState()
            }
        } catch (e: Exception) {
            WearState()
        }
    }

    private fun buildTileLayout(state: WearState): LayoutElementBuilders.LayoutElement {
        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setWidth(DimensionBuilders.expand())
                    .setHeight(DimensionBuilders.wrap())
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .apply {
                        when (state.phase) {
                            "approval_pending" -> {
                                val remainingMs = state.expiryMs - System.currentTimeMillis()
                                val remainingSec = (remainingMs / 1000).coerceAtLeast(0)

                                addContent(brandMark(APPROVAL_MARK_RESOURCE))
                                addContent(text("Approval"))
                                addContent(text(state.summary.takeIf { it.isNotBlank() } ?: "Action pending", maxLines = 2))
                                addContent(text("${remainingSec}s left"))
                                addContent(
                                    LayoutElementBuilders.Row.Builder()
                                        .setWidth(DimensionBuilders.wrap())
                                        .setHeight(DimensionBuilders.wrap())
                                        .addContent(actionChip("Approve", state.actionId, "approve"))
                                        .addContent(actionChip("Reject", state.actionId, "reject"))
                                        .build()
                                )
                            }
                            "listening" -> {
                                addContent(brandMark(APPROVAL_MARK_RESOURCE))
                                addContent(text("Listening..."))
                            }
                            "responding" -> {
                                addContent(brandMark(APPROVAL_MARK_RESOURCE))
                                addContent(text("Responding"))
                            }
                            else -> {
                                addContent(brandMark(READY_MARK_RESOURCE))
                                addContent(text("DevPods"))
                                addContent(text("Ready"))
                            }
                        }
                    }
                    .build()
            )
            .build()
    }

    private fun text(value: String, maxLines: Int = 1): LayoutElementBuilders.LayoutElement {
        return LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(maxLines)
            .build()
    }

    private fun brandMark(resourceId: String): LayoutElementBuilders.LayoutElement {
        return LayoutElementBuilders.Image.Builder()
            .setResourceId(resourceId)
            .setWidth(DimensionBuilders.dp(56f))
            .setHeight(DimensionBuilders.dp(56f))
            .build()
    }

    private fun actionChip(label: String, actionId: String, action: String): LayoutElementBuilders.LayoutElement {
        return LayoutElementBuilders.Text.Builder()
            .setText(label)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("action_${action}")
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName("com.openclaw.relay.wear")
                                            .setClassName("com.openclaw.relay.wear.WearApprovalActivity")
                                            .addKeyToExtraMapping(
                                                "action",
                                                ActionBuilders.AndroidStringExtra.Builder()
                                                    .setValue(action)
                                                    .build()
                                            )
                                            .addKeyToExtraMapping(
                                                "actionId",
                                                ActionBuilders.AndroidStringExtra.Builder()
                                                    .setValue(actionId)
                                                    .build()
                                            )
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(DimensionBuilders.dp(8f))
                            .setEnd(DimensionBuilders.dp(8f))
                            .setTop(DimensionBuilders.dp(4f))
                            .setBottom(DimensionBuilders.dp(4f))
                            .build()
                    )
                    .build()
            )
            .build()
    }

    data class WearState(
        val phase: String = "idle",
        val summary: String = "",
        val actionId: String = "",
        val expiryMs: Long = 0,
    )
}
