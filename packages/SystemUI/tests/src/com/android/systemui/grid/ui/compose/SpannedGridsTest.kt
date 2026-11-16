/*
 * Copyright (C) 2026 crDroid Android Project
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

package com.android.systemui.grid.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SpannedGridsTest : SysuiTestCase() {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun verticalGrid_centersMixedHeights_withoutMovingNextRow() {
        composeRule.setContent {
            VerticalSpannedGrid(
                columns = 2,
                columnSpacing = 0.dp,
                rowSpacing = 8.dp,
                spans = listOf(1, 1, 1),
                modifier = Modifier.width(160.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { index, _, _, _ ->
                Box(
                    Modifier.fillMaxWidth()
                        .height(if (index == 1) 72.dp else 40.dp)
                        .testTag("tile-$index")
                )
            }
        }

        composeRule.onNodeWithTag("tile-0").assertTopPositionInRootIsEqualTo(16.dp)
        composeRule.onNodeWithTag("tile-1").assertTopPositionInRootIsEqualTo(0.dp)
        composeRule.onNodeWithTag("tile-2").assertTopPositionInRootIsEqualTo(80.dp)
    }

    @Test
    fun verticalGrid_defaultsToTopAlignment() {
        composeRule.setContent {
            VerticalSpannedGrid(
                columns = 2,
                columnSpacing = 0.dp,
                rowSpacing = 0.dp,
                spans = listOf(1, 1),
                modifier = Modifier.width(160.dp),
            ) { index, _, _, _ ->
                Box(
                    Modifier.fillMaxWidth()
                        .height(if (index == 0) 40.dp else 72.dp)
                        .testTag("tile-$index")
                )
            }
        }

        composeRule.onNodeWithTag("tile-0").assertTopPositionInRootIsEqualTo(0.dp)
        composeRule.onNodeWithTag("tile-1").assertTopPositionInRootIsEqualTo(0.dp)
    }
}
