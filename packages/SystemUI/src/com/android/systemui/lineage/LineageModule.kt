/*
 * Copyright (C) 2023 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.lineage

import android.content.Context

import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.AmbientDisplayTile
import com.android.systemui.qs.tiles.AODTile
import com.android.systemui.qs.tiles.CPUInfoTile
import com.android.systemui.qs.tiles.CaffeineTile
import com.android.systemui.qs.tiles.CellularTile
import com.android.systemui.qs.tiles.HeadsUpTile
import com.android.systemui.qs.tiles.OnTheGoTile
import com.android.systemui.qs.tiles.PowerShareTile
import com.android.systemui.qs.tiles.ProfilesTile
import com.android.systemui.qs.tiles.ReadingModeTile
import com.android.systemui.qs.tiles.SoundTile
import com.android.systemui.qs.tiles.SyncTile
import com.android.systemui.qs.tiles.UsbTetherTile
import com.android.systemui.qs.tiles.VpnTile
import com.android.systemui.qs.tiles.WifiTile
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig;
import com.android.systemui.qs.tiles.base.shared.model.QSTilePolicy;
import com.android.systemui.qs.tiles.base.shared.model.QSTileUIConfig;
import com.android.systemui.res.R

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
interface LineageModule {
    /** Inject AmbientDisplayTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(AmbientDisplayTile.TILE_SPEC)
    fun bindAmbientDisplayTile(ambientDisplayTile: AmbientDisplayTile): QSTileImpl<*>

    /** Inject AODTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(AODTile.TILE_SPEC)
    fun bindAODTile(aodTile: AODTile): QSTileImpl<*>

    /** Inject CPUInfoTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(CPUInfoTile.TILE_SPEC)
    fun CPUInfoTile(cpuInfoTile: CPUInfoTile): QSTileImpl<*>

    /** Inject CaffeineTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(CaffeineTile.TILE_SPEC)
    fun bindCaffeineTile(caffeineTile: CaffeineTile): QSTileImpl<*>

    /** Inject CellularTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(CellularTile.TILE_SPEC)
    fun bindCellularTile(cellularTile: CellularTile): QSTileImpl<*>

    /** Inject HeadsUpTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(HeadsUpTile.TILE_SPEC)
    fun bindHeadsUpTile(headsUpTile: HeadsUpTile): QSTileImpl<*>

    /** Inject OnTheGoTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(OnTheGoTile.TILE_SPEC)
    fun bindOnTheGoTile(onTheGoTile: OnTheGoTile): QSTileImpl<*>

    /** Inject PowerShareTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(PowerShareTile.TILE_SPEC)
    fun bindPowerShareTile(powerShareTile: PowerShareTile): QSTileImpl<*>

    /** Inject ProfilesTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(ProfilesTile.TILE_SPEC)
    fun bindProfilesTile(profilesTile: ProfilesTile): QSTileImpl<*>

    /** Inject ReadingModeTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(ReadingModeTile.TILE_SPEC)
    fun bindReadingModeTile(readingModeTile: ReadingModeTile): QSTileImpl<*>

    /** Inject SoundTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(SoundTile.TILE_SPEC)
    fun bindSoundTile(soundTile: SoundTile): QSTileImpl<*>

    /** Inject SyncTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(SyncTile.TILE_SPEC)
    fun bindSyncTile(syncTile: SyncTile): QSTileImpl<*>

    /** Inject UsbTetherTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(UsbTetherTile.TILE_SPEC)
    fun bindUsbTetherTile(usbTetherTile: UsbTetherTile): QSTileImpl<*>

    /** Inject VpnTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(VpnTile.TILE_SPEC)
    fun bindVpnTile(vpnTile: VpnTile): QSTileImpl<*>

    /** Inject WifiTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(WifiTile.TILE_SPEC)
    fun bindWifiTile(wifiTile: WifiTile): QSTileImpl<*>

    companion object {
        @Provides
        @IntoMap
        @StringKey(AmbientDisplayTile.TILE_SPEC)
        fun provideAmbientDisplayTileConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(AmbientDisplayTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_ambient_display,
                    labelRes = R.string.quick_settings_ambient_display_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY
            )
        }

        @Provides
        @IntoMap
        @StringKey(AODTile.TILE_SPEC)
        fun provideAODTileConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(AODTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_aod,
                    labelRes = R.string.quick_settings_aod_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY
            )
        }

        @Provides
        @IntoMap
        @StringKey(CPUInfoTile.TILE_SPEC)
        fun provideCPUInfoConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(CPUInfoTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_cpu_info,
                    labelRes = R.string.quick_settings_cpuinfo_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES
            )
        }

        @Provides
        @IntoMap
        @StringKey(CaffeineTile.TILE_SPEC)
        fun provideCaffeineTileConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(CaffeineTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_caffeine,
                    labelRes = R.string.quick_settings_caffeine_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES
            )
        }

        @Provides
        @IntoMap
        @StringKey(CellularTile.TILE_SPEC)
        fun provideCellularTileConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(CellularTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_swap_vert,
                    labelRes = R.string.quick_settings_cellular_detail_title
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.CONNECTIVITY
            )
        }

        @Provides
        @IntoMap
        @StringKey(HeadsUpTile.TILE_SPEC)
        fun provideHeadsUpTileConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(HeadsUpTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_heads_up,
                    labelRes = R.string.quick_settings_heads_up_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.CONNECTIVITY
            )
        }

        @Provides
        @IntoMap
        @StringKey(OnTheGoTile.TILE_SPEC)
        fun provideOnTheGoTileConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(OnTheGoTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_onthego,
                    labelRes = R.string.global_action_onthego
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.ACCESSIBILITY
            )
        }

        @Provides
        @IntoMap
        @StringKey(PowerShareTile.TILE_SPEC)
        fun providePowerShareTileConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(PowerShareTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_powershare,
                    labelRes = R.string.quick_settings_powershare_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES
            )
        }

        @Provides
        @IntoMap
        @StringKey(ProfilesTile.TILE_SPEC)
        fun provideProfilesTileConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(ProfilesTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_profiles,
                    labelRes = R.string.quick_settings_profiles_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.PRIVACY
            )
        }

        @Provides
        @IntoMap
        @StringKey(ReadingModeTile.TILE_SPEC)
        fun provideReadingModeTileConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(ReadingModeTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_reader,
                    labelRes = R.string.quick_settings_reading_mode
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.ACCESSIBILITY
            )
        }

        @Provides
        @IntoMap
        @StringKey(SoundTile.TILE_SPEC)
        fun provideSoundConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(SoundTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_ringer_audible,
                    labelRes = R.string.quick_settings_sound_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES
            )
        }

        @Provides
        @IntoMap
        @StringKey(SyncTile.TILE_SPEC)
        fun provideSyncTileConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(SyncTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_sync,
                    labelRes = R.string.quick_settings_sync_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.CONNECTIVITY
            )
        }

        @Provides
        @IntoMap
        @StringKey(UsbTetherTile.TILE_SPEC)
        fun provideUsbTetherTileConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(UsbTetherTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_usb_tether,
                    labelRes = R.string.quick_settings_usb_tether_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.ACCESSIBILITY
            )
        }

        @Provides
        @IntoMap
        @StringKey(VpnTile.TILE_SPEC)
        fun provideVpnTileConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(VpnTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_vpn,
                    labelRes = R.string.quick_settings_vpn_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.CONNECTIVITY
            )
        }

        @Provides
        @IntoMap
        @StringKey(WifiTile.TILE_SPEC)
        fun provideWifiTileConfig(uiEventLogger: QsEventLogger, context: Context): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(WifiTile.TILE_SPEC),
		uiConfig = QSTileUIConfig.Resource(
                    iconRes = context.resources.getIdentifier(
                        "ic_signal_wifi_transient_animation", "drawable", "android"
                    ),
                    labelRes = R.string.quick_settings_wifi_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.CONNECTIVITY
            )                       
        }
    }
}
