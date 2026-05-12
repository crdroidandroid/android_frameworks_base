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

package com.android.systemui.common.shared.model

import android.annotation.DrawableRes
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.common.shared.model.Icon.Loaded
import java.util.Objects

/**
 * Models an icon, that can either be already [loaded][Icon.Loaded] or be a [reference]
 * [Icon.Resource] to a resource. In case of [Loaded], the resource ID [res] is optional.
 */
@Stable
public sealed class Icon {
    public abstract val contentDescription: ContentDescription?
    public abstract val resId: Int?

    /**
     * An icon that is already loaded.
     *
     * @param resId The resource ID of the icon. For when we want to have Loaded icon, but still
     *   keep a reference to the resource id. A use case would be for tests that have to compare
     *   animated drawables.
     * @param packageName The package that owns [resId]. Null if it belongs to the current
     *   (Systemui) package.
     */
    public data class Loaded
    @JvmOverloads
    constructor(
        val drawable: Drawable,
        override val contentDescription: ContentDescription?,
        /**
         * Together with [packageName], serves as an id to compare two instances. When provided this
         * is used alongside [contentDescription] to determine equality. This is useful when
         * comparing icons representing the same UI, but with different [drawable] instances.
         */
        @DrawableRes override val resId: Int? = null,
        val packageName: String? = null,
        val isBitmapImage: Boolean = false,
    ) : Icon() {
        init {
            if (packageName != null) {
                require(resId != null) {
                    "resId is required if packageName is not null (got $packageName)"
                }
            }
        }

        override fun equals(other: Any?): Boolean {
            if (other !is Loaded) {
                return false
            }

            // If both icons provide a resId, only use package+resId for identification, so that
            // drawable copies or mutations of the same base resource are considered equal.
            if (this.resId != null && other.resId != null) {
                return this.resId == other.resId &&
                    this.packageName == other.packageName &&
                    this.contentDescription == other.contentDescription &&
                    this.isBitmapImage == other.isBitmapImage
            }

            // Otherwise, compare everything.
            return this.resId == other.resId &&
                this.packageName == other.packageName &&
                this.drawable == other.drawable &&
                this.contentDescription == other.contentDescription &&
                this.isBitmapImage == other.isBitmapImage
        }

        override fun hashCode(): Int {
            return if (resId != null) {
                Objects.hash(resId, packageName, contentDescription, isBitmapImage)
            } else {
                Objects.hash(drawable, contentDescription, isBitmapImage)
            }
        }
    }

    /** An icon that is a reference to a resource belonging to the current (SystemUI) package. */
    public data class Resource(
        @DrawableRes override val resId: Int,
        override val contentDescription: ContentDescription?,
    ) : Icon()
}

/** Returns a copy of the icon with the given [contentDescription]. */
public fun Icon.copy(contentDescription: ContentDescription?): Icon =
    when (this) {
        is Loaded -> this.copy(contentDescription = contentDescription)
        is Icon.Resource -> this.copy(contentDescription = contentDescription)
    }

/**
 * Creates [Icon.Loaded] for a given drawable with an optional [contentDescription], [resId] and
 * [resPackage].
 */
public fun Drawable.asIcon(
    contentDescription: ContentDescription? = null,
    @DrawableRes resId: Int? = null,
    resPackage: String? = null,
    isBitmapImage: Boolean = false,
): Loaded = Loaded(this, contentDescription, resId, resPackage, isBitmapImage)

/**
 * Creates [ImageBitmap] for a given [Icon.Loaded]. It avoids IllegalArgumentException by providing
 * 1x1 bitmap if [Drawable.getIntrinsicWidth] or [Drawable.getIntrinsicHeight] is <= 0
 */
public fun Loaded.asImageBitmap(): ImageBitmap {
    return with(drawable) {
        if (this is BitmapDrawable) {
            bitmap.asImageBitmap()
        } else {
            toBitmap(
                    width = intrinsicWidth.takeIf { it > 0 } ?: 1,
                    height = intrinsicWidth.takeIf { it > 0 } ?: 1,
                )
                .asImageBitmap()
        }
    }
}
