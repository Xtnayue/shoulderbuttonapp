package com.shoulderbutton.mapper.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class ShoulderMapperTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = MappingCoreService.isRunning
        if (isRunning) {
            MappingCoreService.stop(this)
        } else {
            MappingCoreService.start(this)
        }
        // 更新按钮视觉状态
        qsTile?.let { tile ->
            tile.state = if (!isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = if (!isRunning) "肩键映射: 开" else "肩键映射: 关"
            tile.updateTile()
        }
    }

    private fun updateTileState() {
        qsTile?.let { tile ->
            val isRunning = MappingCoreService.isRunning
            tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = if (isRunning) "肩键映射: 开" else "肩键映射: 关"
            tile.updateTile()
        }
    }
}
