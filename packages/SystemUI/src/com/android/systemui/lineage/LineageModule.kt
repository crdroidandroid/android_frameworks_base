/*
 * Copyright (C) 2023-2025 The LineageOS Project
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

import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.AmbientDisplayTile
import com.android.systemui.qs.tiles.AODTile
import com.android.systemui.qs.tiles.CPUInfoTile
import com.android.systemui.qs.tiles.CaffeineTile
import com.android.systemui.qs.tiles.CompassTile
import com.android.systemui.qs.tiles.DataSwitchTile
import com.android.systemui.qs.tiles.FPSInfoTile
import com.android.systemui.qs.tiles.HeadsUpTile
import com.android.systemui.qs.tiles.LocaleTile
import com.android.systemui.qs.tiles.OnTheGoTile
import com.android.systemui.qs.tiles.PowerShareTile
import com.android.systemui.qs.tiles.ProfilesTile
import com.android.systemui.qs.tiles.ReadingModeTile
import com.android.systemui.qs.tiles.RefreshRateTile
import com.android.systemui.qs.tiles.ScreenshotTile
import com.android.systemui.qs.tiles.SmartPixelsTile
import com.android.systemui.qs.tiles.SoundTile
import com.android.systemui.qs.tiles.SyncTile
import com.android.systemui.qs.tiles.UsbTetherTile
import com.android.systemui.qs.tiles.VolumeTile
import com.android.systemui.qs.tiles.VpnTile
import com.android.systemui.qs.tiles.WeatherTile
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig
import com.android.systemui.qs.tiles.base.shared.model.QSTileUIConfig
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

    /** Inject CompassTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(CompassTile.TILE_SPEC)
    fun bindCompassTile(compassTile: CompassTile): QSTileImpl<*>

    /** Inject DataSwitchTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(DataSwitchTile.TILE_SPEC)
    fun bindDataSwitchTile(dataSwitchTile: DataSwitchTile): QSTileImpl<*>

    /** Inject FPSInfoTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(FPSInfoTile.TILE_SPEC)
    fun FPSInfoTile(fpsInfoTile: FPSInfoTile): QSTileImpl<*>

    /** Inject HeadsUpTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(HeadsUpTile.TILE_SPEC)
    fun bindHeadsUpTile(headsUpTile: HeadsUpTile): QSTileImpl<*>

    /** Inject LocaleTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(LocaleTile.TILE_SPEC)
    fun bindLocaleTile(localeTile: LocaleTile): QSTileImpl<*>

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

    /** Inject RefreshRateTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(RefreshRateTile.TILE_SPEC)
    fun bindRefreshRateTile(refreshRateTile: RefreshRateTile): QSTileImpl<*>

    /** Inject ScreenshotTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(ScreenshotTile.TILE_SPEC)
    fun bindScreenshotTile(screenshotTile: ScreenshotTile): QSTileImpl<*>

    /** Inject SmartPixelsTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(SmartPixelsTile.TILE_SPEC)
    fun bindSmartPixelsTile(smartPixelsTile: SmartPixelsTile): QSTileImpl<*>

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

    /** Inject VolumeTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(VolumeTile.TILE_SPEC)
    fun bindVolumeTile(volumeTile: VolumeTile): QSTileImpl<*>

    /** Inject VpnTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(VpnTile.TILE_SPEC)
    fun bindVpnTile(vpnTile: VpnTile): QSTileImpl<*>

    /** Inject WeatherTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(WeatherTile.TILE_SPEC)
    fun bindWeatherTile(weatherTile: WeatherTile): QSTileImpl<*>

    companion object {
        const val AMBIENT_DISPLAY_TILE_SPEC = "ambient_display"
        const val AOD_TILE_SPEC = "aod"
        const val CAFFEINE_TILE_SPEC = "caffeine"
        const val HEADS_UP_TILE_SPEC = "heads_up"
        const val POWERSHARE_TILE_SPEC = "powershare"
        const val PROFILES_TILE_SPEC = "profiles"
        const val READING_MODE_TILE_SPEC = "reading_mode"
        const val SYNC_TILE_SPEC = "sync"
        const val USB_TETHER_TILE_SPEC = "usb_tether"
        const val VPN_TILE_SPEC = "vpn"

        @Provides
        @IntoMap
        @StringKey(AMBIENT_DISPLAY_TILE_SPEC)
        fun provideAmbientDisplayTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(AMBIENT_DISPLAY_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_ambient_display,
                        labelRes = R.string.quick_settings_ambient_display_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY,
            )

        @Provides
        @IntoMap
        @StringKey(AOD_TILE_SPEC)
        fun provideAodTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(AOD_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_aod,
                        labelRes = R.string.quick_settings_aod_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY,
            )

        @Provides
        @IntoMap
        @StringKey(CPUInfoTile.TILE_SPEC)
        fun provideCPUInfoConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(CPUInfoTile.TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_cpu_info,
                        labelRes = R.string.quick_settings_cpuinfo_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES,
            )

        @Provides
        @IntoMap
        @StringKey(CAFFEINE_TILE_SPEC)
        fun provideCaffeineTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(CAFFEINE_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_caffeine,
                        labelRes = R.string.quick_settings_caffeine_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY,
            )

        @Provides
        @IntoMap
        @StringKey(CompassTile.TILE_SPEC)
        fun provideCompassTile(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(CompassTile.TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_compass,
                        labelRes = R.string.quick_settings_compass_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES,
            )

        @Provides
        @IntoMap
        @StringKey(DataSwitchTile.TILE_SPEC)
        fun provideDataSwitchTile(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(DataSwitchTile.TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_data_switch_0,
                        labelRes = R.string.qs_data_switch_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.CONNECTIVITY,
            )

        @Provides
        @IntoMap
        @StringKey(FPSInfoTile.TILE_SPEC)
        fun provideFPSInfoConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(FPSInfoTile.TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_fps_info,
                        labelRes = R.string.quick_settings_fpsinfo_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES,
            )

        @Provides
        @IntoMap
        @StringKey(HEADS_UP_TILE_SPEC)
        fun provideHeadsUpTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(HEADS_UP_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_heads_up,
                        labelRes = R.string.quick_settings_heads_up_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.ACCESSIBILITY,
            )

        @Provides
        @IntoMap
        @StringKey(LocaleTile.TILE_SPEC)
        fun provideLocaleTile(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(LocaleTile.TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_locale,
                        labelRes = R.string.quick_settings_locale_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.ACCESSIBILITY,
            )

        @Provides
        @IntoMap
        @StringKey(OnTheGoTile.TILE_SPEC)
        fun provideOnTheGoTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(OnTheGoTile.TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_onthego,
                        labelRes = R.string.global_action_onthego
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.ACCESSIBILITY,
            )

        @Provides
        @IntoMap
        @StringKey(POWERSHARE_TILE_SPEC)
        fun providePowershareTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(POWERSHARE_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_powershare,
                        labelRes = R.string.quick_settings_powershare_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES,
            )

        @Provides
        @IntoMap
        @StringKey(PROFILES_TILE_SPEC)
        fun provideProfilesTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(PROFILES_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_profiles,
                        labelRes = R.string.quick_settings_profiles_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES,
            )

        @Provides
        @IntoMap
        @StringKey(READING_MODE_TILE_SPEC)
        fun provideReadingModeTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(READING_MODE_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_reader,
                        labelRes = R.string.quick_settings_reading_mode
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY,
            )

        @Provides
        @IntoMap
        @StringKey(RefreshRateTile.TILE_SPEC)
        fun provideRefreshRateTile(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(RefreshRateTile.TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_refresh_rate,
                        labelRes = R.string.refresh_rate_tile_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY,
            )

        @Provides
        @IntoMap
        @StringKey(ScreenshotTile.TILE_SPEC)
        fun provideScreenshotTile(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(ScreenshotTile.TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = com.android.internal.R.drawable.ic_screenshot,
                        labelRes = com.android.internal.R.string.global_action_screenshot
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES,
            )

        @Provides
        @IntoMap
        @StringKey(SmartPixelsTile.TILE_SPEC)
        fun provideSmartPixelsTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(SmartPixelsTile.TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_smart_pixels,
                        labelRes = R.string.quick_settings_smart_pixels
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY,
            )

        @Provides
        @IntoMap
        @StringKey(SoundTile.TILE_SPEC)
        fun provideSoundConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(SoundTile.TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_ringer_audible,
                        labelRes = R.string.quick_settings_sound_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES,
            )

        @Provides
        @IntoMap
        @StringKey(SYNC_TILE_SPEC)
        fun provideSyncTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(SYNC_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_sync,
                        labelRes = R.string.quick_settings_sync_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.CONNECTIVITY,
            )

        @Provides
        @IntoMap
        @StringKey(USB_TETHER_TILE_SPEC)
        fun provideUsbTetherTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(USB_TETHER_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_usb_tether,
                        labelRes = R.string.quick_settings_usb_tether_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.CONNECTIVITY,
            )

        @Provides
        @IntoMap
        @StringKey(VolumeTile.TILE_SPEC)
        fun provideVolumeTile(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(VolumeTile.TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_volume_panel,
                        labelRes = R.string.quick_settings_volume_panel_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES,
            )

        @Provides
        @IntoMap
        @StringKey(VPN_TILE_SPEC)
        fun provideVpnTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(VPN_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_vpn,
                        labelRes = R.string.quick_settings_vpn_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.CONNECTIVITY,
            )

        @Provides
        @IntoMap
        @StringKey(WeatherTile.TILE_SPEC)
        fun provideWeatherTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(WeatherTile.TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_weather,
                        labelRes = R.string.omnijaws_label_default
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES,
            )
    }
}
