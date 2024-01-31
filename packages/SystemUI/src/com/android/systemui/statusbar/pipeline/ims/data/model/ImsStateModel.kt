/*
 * Copyright (C) 2024 The LibreMobileOS Foundation
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
package com.android.systemui.statusbar.pipeline.ims.data.model

import android.telephony.ims.feature.MmTelFeature
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_NONE

data class ImsStateModel(
    val subId: Int = -1,
    val slotIndex: Int = -1,
    val activeSubCount: Int = 0,
    val registered: Boolean = false,
    val capabilities: MmTelFeature.MmTelCapabilities? = null,
    val registrationTech: Int = REGISTRATION_TECH_NONE
) {

    fun isHdVoiceCapable(): Boolean =
        registered && capabilities
            ?.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE) ?: false

    fun isVoWifiAvailable(): Boolean =
        isHdVoiceCapable() && registrationTech == REGISTRATION_TECH_IWLAN

}
