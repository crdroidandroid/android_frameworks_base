package com.android.systemui.volume.panel.component.appvolume.ui.viewmodel

import android.media.AppVolume
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.volume.data.repository.fakeAudioRepository
import com.android.systemui.volume.panel.component.appvolume.domain.interactor.appVolumePanelGlobalStateInteractor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
class AppVolumePanelViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private fun initUnderTest(): AppVolumePanelViewModel {
        return with(kosmos) { appVolumePanelViewModelFactory.create(testScope.backgroundScope) }
    }

    @Test
    fun sliderViewModels_filtersInactiveSessions() =
        with(kosmos) {
            testScope.runTest {
                fakeAudioRepository.setAppVolumeSessions(
                    listOf(
                        appVolume(packageName = "com.active", isActive = true),
                        appVolume(packageName = "com.inactive", isActive = false),
                    )
                )

                val underTest = initUnderTest()
                val sliders by collectLastValue(underTest.sliderViewModels)
                runCurrent()

                assertThat(sliders).hasSize(1)
            }
        }

    @Test
    fun sliderViewModels_emptyWhenNoActiveSessions() =
        with(kosmos) {
            testScope.runTest {
                fakeAudioRepository.setAppVolumeSessions(emptyList())

                val underTest = initUnderTest()
                val sliders by collectLastValue(underTest.sliderViewModels)
                runCurrent()

                assertThat(sliders).isEmpty()
            }
        }

    @Test
    fun onDoneClicked_hidesPanel() =
        with(kosmos) {
            testScope.runTest {
                appVolumePanelGlobalStateInteractor.setVisible(true)
                val underTest = initUnderTest()

                underTest.onDoneClicked()
                runCurrent()

                assertThat(appVolumePanelGlobalStateInteractor.globalState.value.isVisible)
                    .isFalse()
            }
        }

    private fun appVolume(packageName: String, isActive: Boolean): AppVolume {
        return mock {
            on { getPackageName() } doReturn packageName
            on { isActive } doReturn isActive
            on { volume } doReturn 0.5f
            on { isMuted } doReturn false
        }
    }
}
