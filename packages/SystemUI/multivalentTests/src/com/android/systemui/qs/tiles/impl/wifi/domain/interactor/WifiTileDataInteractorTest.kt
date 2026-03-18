/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.tiles.impl.wifi.domain.interactor

import android.os.UserHandle
import android.platform.test.annotations.RequiresFlagsDisabled
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.systemui.Flags as AconfigFlags
import com.android.systemui.Flags.FLAG_QS_SPLIT_INTERNET_TILE
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.table.logcatTableLogBuffer
import com.android.systemui.qs.tiles.impl.wifi.domain.model.WifiTileModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.connectivity.WifiIcons
import com.android.systemui.statusbar.connectivity.ui.MobileContextProvider
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorImpl
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractorImpl
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiTileIconModel
import com.android.systemui.statusbar.policy.data.repository.FakeUserSetupRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.CarrierConfigTracker
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class WifiTileDataInteractorTest(flags: FlagsParameterization) : SysuiTestCase() {

    @get:Rule val checkFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var underTest: WifiTileDataInteractor
    private lateinit var mobileIconsInteractor: MobileIconsInteractor
    private lateinit var mobileConnectionsRepository: FakeMobileConnectionsRepository
    private lateinit var mobileConnectionRepository: FakeMobileConnectionRepository
    private lateinit var connectivityRepository: FakeConnectivityRepository
    private lateinit var wifiRepository: FakeWifiRepository

    @Before
    fun setUp() {
        val mobileContextProvider = mock<MobileContextProvider>()
        whenever(mobileContextProvider.getMobileContextForSub(any(), any())).thenReturn(context)

        val tableLogBuffer = logcatTableLogBuffer(kosmos, "WifiTileDataInteractorTest")
        mobileConnectionsRepository =
            FakeMobileConnectionsRepository(FakeMobileMappingsProxy(), tableLogBuffer)
        mobileConnectionRepository = FakeMobileConnectionRepository(SUB_ID, tableLogBuffer)
        connectivityRepository = FakeConnectivityRepository()
        wifiRepository = FakeWifiRepository()

        val wifiInteractor =
            WifiInteractorImpl(connectivityRepository, wifiRepository, testScope.backgroundScope)

        mobileIconsInteractor =
            MobileIconsInteractorImpl(
                mobileConnectionsRepository,
                mock<CarrierConfigTracker>(),
                tableLogBuffer,
                connectivityRepository,
                FakeUserSetupRepository(),
                testScope.backgroundScope,
                context,
                FakeFeatureFlagsClassic().also {
                    it.set(Flags.FILTER_PROVISIONING_NETWORK_SUBSCRIPTIONS, true)
                },
            )

        underTest =
            WifiTileDataInteractor(
                context,
                testScope.backgroundScope,
                FakeAirplaneModeRepository(),
                connectivityRepository,
                mobileIconsInteractor,
                mobileContextProvider,
                wifiInteractor,
            )
    }

    @Test
    fun tileData_wifiActiveAndDefault_showsSsid() =
        kosmos.runTest {
            val tileData by collectLastValue(underTest.tileData())

            connectivityRepository.setWifiConnected()
            wifiRepository.setIsWifiDefault(true)
            wifiRepository.setWifiNetwork(
                WifiNetworkModel.Active.of(isValidated = true, level = 4, ssid = "Test SSID")
            )

            val expectedModel =
                WifiTileModel.Active(
                    icon = WifiTileIconModel(WifiIcons.WIFI_FULL_ICONS[4]),
                    secondaryLabel = "Test SSID",
                )
            assertThat(tileData).isEqualTo(expectedModel)
        }

    @Test
    fun tileData_wifiDisabled_showsOffState() =
        kosmos.runTest {
            val tileData by collectLastValue(underTest.tileData())

            wifiRepository.setIsWifiEnabled(false)

            val expectedModel =
                WifiTileModel.Inactive(
                    icon = WifiTileIconModel(R.drawable.ic_signal_wifi_off),
                    secondaryLabel = context.getString(R.string.quick_settings_networks_unavailable),
                )
            assertThat(tileData?.icon).isEqualTo(expectedModel.icon)
            assertThat(tileData?.secondaryLabel).isEqualTo(expectedModel.secondaryLabel)
        }

    @Test
    fun tileData_ethernetIsDefault_showsEthernet() =
        kosmos.runTest {
            val tileData by collectLastValue(underTest.tileData())
            wifiRepository.setWifiNetwork(WifiNetworkModel.Inactive())
            connectivityRepository.setEthernetConnected()

            val expectedModel =
                WifiTileModel.Active(
                    icon = WifiTileIconModel(R.drawable.stat_sys_ethernet_fully),
                    secondaryLabel =
                        context.getString(
                            com.android.settingslib.R.string.accessibility_ethernet_connected
                        ),
                )
            assertThat(tileData).isEqualTo(expectedModel)
        }

    @Test
    fun tileData_mobileIsDefault_showsWifiSpecificFallbackSubtitle() =
        kosmos.runTest {
            val tileData by collectLastValue(underTest.tileData())

            wifiRepository.setIsWifiEnabled(true)
            wifiRepository.setWifiNetwork(WifiNetworkModel.Inactive())

            connectivityRepository.setMobileConnected()
            mobileConnectionsRepository.apply {
                activeMobileDataRepository.value = mobileConnectionRepository
                activeMobileDataSubscriptionId.value = SUB_ID
                setMobileConnectionRepositoryMap(mapOf(SUB_ID to mobileConnectionRepository))
            }
            mobileConnectionRepository.apply {
                isInService.value = true
                dataConnectionState.value = DataConnectionState.Connected
                dataEnabled.value = true
                networkName.value = NetworkNameModel.Default("Test Mobile")
            }

            val expectedModel =
                WifiTileModel.Inactive(
                    icon = WifiTileIconModel(WifiIcons.WIFI_NO_SIGNAL),
                    secondaryLabel = context.getString(R.string.quick_settings_networks_unavailable),
                )

            assertThat(tileData?.icon).isEqualTo(expectedModel.icon)
            assertThat(tileData?.secondaryLabel).isEqualTo(expectedModel.secondaryLabel)
        }

    @Test
    @RequiresFlagsEnabled(FLAG_QS_SPLIT_INTERNET_TILE)
    fun availability_flagEnabled_isTrue() =
        kosmos.runTest {
            assertThat(AconfigFlags.qsSplitInternetTile()).isTrue()
            val availability by collectLastValue(underTest.availability(USER))
            assertThat(availability).isTrue()
        }

    @Test
    @RequiresFlagsDisabled(FLAG_QS_SPLIT_INTERNET_TILE)
    fun availability_flagDisabled_isFalse() =
        kosmos.runTest {
            assertThat(AconfigFlags.qsSplitInternetTile()).isFalse()
            val availability by collectLastValue(underTest.availability(USER))
            assertThat(availability).isFalse()
        }

    companion object {
        private const val SUB_ID = 456
        private val USER = UserHandle.of(0)

        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }
}
