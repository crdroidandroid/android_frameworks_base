/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.dagger

import android.net.wifi.WifiManager
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.KairosNetwork
import com.android.systemui.kairos.toColdConflatedFlow
import com.android.systemui.kairos.util.nameTag
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.TableLogBufferFactory
import com.android.systemui.statusbar.events.data.repository.SystemStatusEventAnimationRepository
import com.android.systemui.statusbar.events.data.repository.SystemStatusEventAnimationRepositoryImpl
import com.android.systemui.statusbar.pipeline.airplane.ui.viewmodel.AirplaneModeViewModel
import com.android.systemui.statusbar.pipeline.airplane.ui.viewmodel.AirplaneModeViewModelImpl
import com.android.systemui.statusbar.pipeline.battery.data.repository.BatteryRepository
import com.android.systemui.statusbar.pipeline.battery.data.repository.BatteryRepositoryImpl
import com.android.systemui.statusbar.pipeline.icons.shared.BindableIconsRegistry
import com.android.systemui.statusbar.pipeline.icons.shared.BindableIconsRegistryImpl
import com.android.systemui.statusbar.pipeline.ims.data.repository.CommonImsRepository
import com.android.systemui.statusbar.pipeline.ims.data.repository.CommonImsRepositoryImpl
import com.android.systemui.statusbar.pipeline.mobile.StatusBarMobileIconKairos
import com.android.systemui.statusbar.pipeline.mobile.data.repository.CarrierConfigCoreStartable
import com.android.systemui.statusbar.pipeline.mobile.data.repository.CarrierConfigRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.CarrierConfigRepositoryImpl
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepositoryKairosAdapter
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileRepositorySwitcher
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileRepositorySwitcherKairos
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.DemoModeMobileConnectionDataSourceKairosImpl
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.MobileConnectionRepositoryKairosFactoryImpl
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.MobileConnectionsRepositoryKairosImpl
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorImpl
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorKairosAdapter
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorKairosImpl
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapterKairos
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModelKairos
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxyImpl
import com.android.systemui.statusbar.pipeline.mobile.util.SubscriptionManagerProxy
import com.android.systemui.statusbar.pipeline.mobile.util.SubscriptionManagerProxyImpl
import com.android.systemui.statusbar.pipeline.satellite.data.DeviceBasedSatelliteRepository
import com.android.systemui.statusbar.pipeline.satellite.data.DeviceBasedSatelliteRepositorySwitcher
import com.android.systemui.statusbar.pipeline.satellite.data.RealDeviceBasedSatelliteRepository
import com.android.systemui.statusbar.pipeline.satellite.data.prod.DeviceBasedSatelliteRepositoryImpl
import com.android.systemui.statusbar.pipeline.satellite.ui.viewmodel.DeviceBasedSatelliteViewModel
import com.android.systemui.statusbar.pipeline.satellite.ui.viewmodel.DeviceBasedSatelliteViewModelImpl
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstantsImpl
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepositoryImpl
import com.android.systemui.statusbar.pipeline.wifi.data.repository.RealWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepositorySwitcher
import com.android.systemui.statusbar.pipeline.wifi.data.repository.demo.DemoModeWifiDataSourceKairos
import com.android.systemui.statusbar.pipeline.wifi.data.repository.prod.DisabledWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.prod.WifiRepositoryImpl
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractorImpl
import com.android.systemui.statusbar.policy.data.repository.UserSetupRepository
import com.android.systemui.statusbar.policy.data.repository.UserSetupRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import java.util.function.Supplier
import javax.inject.Named
import javax.inject.Provider
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalKairosApi::class)
@Module(
    includes =
        [
            DemoModeMobileConnectionDataSourceKairosImpl.Module::class,
            DemoModeWifiDataSourceKairos.Module::class,
            MobileRepositorySwitcherKairos.Module::class,
            MobileConnectionsRepositoryKairosImpl.Module::class,
            MobileIconsInteractorKairosImpl.Module::class,
            MobileIconsViewModelKairos.Module::class,
            MobileConnectionRepositoryKairosFactoryImpl.Module::class,
            MobileConnectionsRepositoryKairosAdapter.Module::class,
            MobileIconsInteractorKairosAdapter.Module::class,
            MobileUiAdapterKairos.Module::class,
        ]
)
abstract class StatusBarPipelineModule {
    @Binds
    abstract fun airplaneModeViewModel(impl: AirplaneModeViewModelImpl): AirplaneModeViewModel

    @Binds
    abstract fun bindableIconsRepository(impl: BindableIconsRegistryImpl): BindableIconsRegistry

    @Binds
    abstract fun connectivityRepository(impl: ConnectivityRepositoryImpl): ConnectivityRepository

    @Binds abstract fun batteryRepository(impl: BatteryRepositoryImpl): BatteryRepository

    @Binds
    abstract fun systemStatusEventAnimationRepository(
        impl: SystemStatusEventAnimationRepositoryImpl
    ): SystemStatusEventAnimationRepository

    @Binds
    abstract fun realDeviceBasedSatelliteRepository(
        impl: DeviceBasedSatelliteRepositoryImpl
    ): RealDeviceBasedSatelliteRepository

    @Binds
    abstract fun deviceBasedSatelliteRepository(
        impl: DeviceBasedSatelliteRepositorySwitcher
    ): DeviceBasedSatelliteRepository

    @Binds
    abstract fun deviceBasedSatelliteViewModel(
        impl: DeviceBasedSatelliteViewModelImpl
    ): DeviceBasedSatelliteViewModel

    @Binds
    abstract fun connectivityConstants(impl: ConnectivityConstantsImpl): ConnectivityConstants

    @Binds abstract fun wifiRepository(impl: WifiRepositorySwitcher): WifiRepository

    @Binds abstract fun wifiInteractor(impl: WifiInteractorImpl): WifiInteractor

    @Binds abstract fun userSetupRepository(impl: UserSetupRepositoryImpl): UserSetupRepository

    @Binds abstract fun mobileMappingsProxy(impl: MobileMappingsProxyImpl): MobileMappingsProxy

    @Binds
    abstract fun carrierConfigRepository(impl: CarrierConfigRepositoryImpl): CarrierConfigRepository

    @Binds
    abstract fun subscriptionManagerProxy(
        impl: SubscriptionManagerProxyImpl
    ): SubscriptionManagerProxy

    @Binds
    @IntoMap
    @ClassKey(MobileUiAdapter::class)
    abstract fun bindFeature(impl: MobileUiAdapter): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(CarrierConfigCoreStartable::class)
    abstract fun bindCarrierConfigStartable(impl: CarrierConfigCoreStartable): CoreStartable

    @Binds
    abstract fun bindCommonImsRepository(impl: CommonImsRepositoryImpl): CommonImsRepository

    companion object {

        @Provides
        fun mobileIconsInteractor(
            impl: Provider<MobileIconsInteractorImpl>,
            kairosImpl: Provider<MobileIconsInteractorKairosAdapter>,
        ): MobileIconsInteractor {
            return if (StatusBarMobileIconKairos.isEnabled) {
                kairosImpl.get()
            } else {
                impl.get()
            }
        }

        @Provides
        fun mobileConnectionsRepository(
            impl: Provider<MobileRepositorySwitcher>,
            kairosImpl: Provider<MobileConnectionsRepositoryKairosAdapter>,
        ): MobileConnectionsRepository {
            return if (StatusBarMobileIconKairos.isEnabled) {
                kairosImpl.get()
            } else {
                impl.get()
            }
        }

        @Provides
        @SysUISingleton
        fun provideRealWifiRepository(
            wifiManager: WifiManager?,
            disabledWifiRepository: DisabledWifiRepository,
            wifiRepositoryImplFactory: WifiRepositoryImpl.Factory,
        ): RealWifiRepository {
            // If we have a null [WifiManager], then the wifi repository should be permanently
            // disabled.
            return if (wifiManager == null) {
                disabledWifiRepository
            } else {
                wifiRepositoryImplFactory.create(wifiManager)
            }
        }

        @Provides
        @SysUISingleton
        @Named(FIRST_MOBILE_SUB_SHOWING_NETWORK_TYPE_ICON)
        fun provideFirstMobileSubShowingNetworkTypeIconProvider(
            mobileIconsViewModel: Provider<MobileIconsViewModel>,
            kairosViewModel: Provider<MobileIconsViewModelKairos>,
            kairosNetwork: KairosNetwork,
        ): Supplier<Flow<Boolean>> {
            return if (StatusBarMobileIconKairos.isEnabled) {
                Supplier {
                    kairosViewModel
                        .get()
                        .firstMobileSubShowingNetworkTypeIcon
                        .toColdConflatedFlow(
                            kairosNetwork,
                            name =
                                nameTag(
                                    "StatusBarPipelineModule.provideFirstMobileSubShowingNetworkTypeIconProvider"
                                ),
                        )
                }
            } else {
                Supplier { mobileIconsViewModel.get().firstMobileSubShowingNetworkTypeIcon }
            }
        }

        @Provides
        @SysUISingleton
        @WifiInputLog
        fun provideWifiLogBuffer(factory: LogBufferFactory): LogBuffer {
            return factory.create("WifiInputLog", 200)
        }

        @Provides
        @SysUISingleton
        @WifiTableLog
        fun provideWifiTableLogBuffer(factory: TableLogBufferFactory): TableLogBuffer {
            return factory.create("WifiTableLog", 100)
        }

        @Provides
        @SysUISingleton
        @SharedConnectivityInputLog
        fun provideSharedConnectivityTableLogBuffer(factory: LogBufferFactory): LogBuffer {
            return factory.create("SharedConnectivityInputLog", 60)
        }

        @Provides
        @SysUISingleton
        @MobileSummaryLog
        fun provideMobileSummaryLogBuffer(factory: TableLogBufferFactory): TableLogBuffer {
            return factory.create("MobileSummaryLog", 200)
        }

        @Provides
        @SysUISingleton
        @MobileInputLog
        fun provideMobileInputLogBuffer(factory: LogBufferFactory): LogBuffer {
            return factory.create("MobileInputLog", 300)
        }

        @Provides
        @SysUISingleton
        @MobileViewLog
        fun provideMobileViewLogBuffer(factory: LogBufferFactory): LogBuffer {
            return factory.create("MobileViewLog", 100)
        }

        @Provides
        @SysUISingleton
        @VerboseMobileViewLog
        fun provideVerboseMobileViewLogBuffer(factory: LogBufferFactory): LogBuffer {
            return factory.create("VerboseMobileViewLog", 200)
        }

        @Provides
        @SysUISingleton
        @DeviceBasedSatelliteInputLog
        fun provideDeviceBasedSatelliteInputLog(factory: LogBufferFactory): LogBuffer {
            return factory.create("DeviceBasedSatelliteInputLog", 200)
        }

        @Provides
        @SysUISingleton
        @VerboseDeviceBasedSatelliteInputLog
        fun provideVerboseDeviceBasedSatelliteInputLog(factory: LogBufferFactory): LogBuffer {
            return factory.create("VerboseDeviceBasedSatelliteInputLog", 200)
        }

        @Provides
        @SysUISingleton
        @DeviceBasedSatelliteTableLog
        fun provideDeviceBasedSatelliteTableLog(factory: TableLogBufferFactory): TableLogBuffer {
            return factory.create("DeviceBasedSatelliteTableLog", 200)
        }

        @Provides
        @SysUISingleton
        @StackedMobileIconTableLog
        fun provideStackedMobileIconTableLog(factory: TableLogBufferFactory): TableLogBuffer {
            return factory.create("StackedMobileIconTableLog", 100)
        }

        @Provides
        @SysUISingleton
        @BatteryTableLog
        fun provideBatteryTableLog(factory: TableLogBufferFactory): TableLogBuffer {
            return factory.create("BatteryTableLog", 100)
        }

        const val FIRST_MOBILE_SUB_SHOWING_NETWORK_TYPE_ICON =
            "FirstMobileSubShowingNetworkTypeIcon"
    }
}
