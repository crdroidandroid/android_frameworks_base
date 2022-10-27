/*
 * Copyright (C) 2021 The Android Open Source Project
 *           (C) 2022 Paranoid Android
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

package com.android.systemui.qs.tiles.dialog;

import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.Utils;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.systemui.R;

import java.util.List;

/**
 * Adapter for showing bluetooth devices.
 */
public class BluetoothViewAdapter extends
        RecyclerView.Adapter<BluetoothViewAdapter.BluetoothViewHolder> {

    private static final String DEVICE_DETAIL_INTENT =
            "com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS";
    private static final String KEY_DEVICE_ADDRESS = "device_address";
    private static final String EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args";

    @Nullable
    private List<CachedBluetoothDevice> mDevices;
    private int mDevicesCount;
    private int mMaxDevicesCount = BluetoothDialog.MAX_DEVICES_COUNT;
    private CachedBluetoothDevice mActiveDevice;

    private BluetoothDialog mDialog;

    private View mHolderView;
    private Context mContext;

    public BluetoothViewAdapter(BluetoothDialog dialog) {
        mDialog = dialog;
    }

    @Override
    public BluetoothViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup,
            int viewType) {
        mContext = viewGroup.getContext();
        mHolderView = LayoutInflater.from(mContext).inflate(R.layout.bluetooth_list_item,
                viewGroup, false);
        return new BluetoothViewHolder(mHolderView, mDialog);
    }

    @Override
    public void onBindViewHolder(@NonNull BluetoothViewHolder viewHolder, int position) {
        if (mDevices == null || position >= mDevicesCount) {
            return;
        }
        CachedBluetoothDevice device = mDevices.get(position);
        final boolean isActive = mActiveDevice != null && position == 0;
        if (isActive) {
            device = mActiveDevice;
        } else if (device == mActiveDevice) {
            device = mDevices.get(0);
        }
        viewHolder.onBind(device, isActive);
    }

    /**
     * Updates the connected bluetooth devices.
     *
     * @param devices the updated bluetooth devices.
     */
    public void setBluetoothDevices(List<CachedBluetoothDevice> devices) {
        if (mDevices == devices) {
            return;
        }
        mDevices = devices;
        mDevicesCount = Math.min(devices.size(), mMaxDevicesCount);
        notifyDataSetChanged();
    }

    public void setActiveDevice(@Nullable CachedBluetoothDevice device) {
        if (mActiveDevice == device) {
            return;
        }
        mActiveDevice = device;
        notifyDataSetChanged();
    }

    /**
     * Gets the total number of bluetooth devices.
     *
     * @return The total number of bluetooth devices.
     */
    @Override
    public int getItemCount() {
        return mDevicesCount;
    }

    /**
     * ViewHolder for binding bluetooth view.
     */
    static class BluetoothViewHolder extends RecyclerView.ViewHolder {

        private final LinearLayout mContainerLayout, mBluetoothListLayout, mBluetoothDeviceLayout;
        private final ImageView mBluetoothIcon;
        private final TextView mBluetoothTitleText, mBluetoothSummaryText;
        private final FrameLayout mDisconnectIconLayout;
        private final ImageView mDisconnectIcon, mBluetoothEndIcon;
        private final Context mContext;
        private final Drawable mBackgroundOn, mBackgroundOff;
        private final BluetoothDialog mDialog;

        BluetoothViewHolder(View view, BluetoothDialog dialog) {
            super(view);
            mContext = view.getContext();
            mDialog = dialog;
            mContainerLayout = view.requireViewById(R.id.bluetooth_container);
            mBluetoothListLayout = view.requireViewById(R.id.bluetooth_list);
            mBluetoothDeviceLayout = view.requireViewById(R.id.bluetooth_device_layout);
            mBluetoothIcon = view.requireViewById(R.id.bluetooth_icon);
            mBluetoothTitleText = view.requireViewById(R.id.bluetooth_title);
            mBluetoothSummaryText = view.requireViewById(R.id.bluetooth_summary);
            mDisconnectIconLayout = view.requireViewById(R.id.bluetooth_disconnect_icon_layout);
            mDisconnectIcon = view.requireViewById(R.id.bluetooth_disconnect_icon);
            mBluetoothEndIcon = view.requireViewById(R.id.bluetooth_end_icon);
            mBackgroundOn = mContext.getDrawable(R.drawable.settingslib_switch_bar_bg_on);

            try (final TypedArray typedArray = mContext.obtainStyledAttributes(
                    new int[]{android.R.attr.selectableItemBackground})) {
                mBackgroundOff = typedArray.getDrawable(0 /* index */);
            }
        }

        void onBind(@NonNull CachedBluetoothDevice device, boolean isActive) {
            if (device == null || !device.hasHumanReadableName()) {
                mBluetoothListLayout.setVisibility(View.GONE);
                return;
            }
            mBluetoothListLayout.setVisibility(View.VISIBLE);
            mBluetoothListLayout.setBackground(isActive ? mBackgroundOn : mBackgroundOff);
            mBluetoothListLayout.setOnClickListener(v -> {
                if (device.isConnected()) {
                    device.setActive();
                } else {
                    device.connect();
                }
            });

            // device icon
            mBluetoothIcon.setImageDrawable(device.getDrawableWithDescription().first);

            // title
            mBluetoothTitleText.setText(device.getName());
            mBluetoothTitleText.setTextAppearance(isActive
                    ? R.style.TextAppearance_InternetDialog_Active
                    : R.style.TextAppearance_InternetDialog);

            // summary
            final String summary = device.getConnectionSummary();
            final boolean showSummary = !TextUtils.isEmpty(summary);
            if (showSummary) {
                mBluetoothSummaryText.setText(summary);
                mBluetoothSummaryText.setTextAppearance(isActive
                        ? R.style.TextAppearance_InternetDialog_Secondary_Active
                        : R.style.TextAppearance_InternetDialog_Secondary);
            }
            mBluetoothSummaryText.setVisibility(showSummary ? View.VISIBLE : View.GONE);

            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)
                    mBluetoothListLayout.getLayoutParams();
            lp.setMargins(0, 0, 0, isActive ? mContext.getResources().getDimensionPixelSize(
                R.dimen.bluetooth_dialog_active_device_bottom_margin) : 0);
            lp.height = mContext.getResources().getDimensionPixelSize(
                R.dimen.internet_dialog_wifi_network_height);
            mBluetoothListLayout.setLayoutParams(lp);

            mDisconnectIconLayout.setVisibility(device.isConnected() ? View.VISIBLE : View.GONE);
            mDisconnectIcon.setOnClickListener(v -> device.disconnect());

            final int iconColor = isActive ? mContext.getColor(R.color.connected_network_primary_color)
                    : Utils.getColorAttrDefaultColor(mContext, android.R.attr.colorControlNormal);
            mDisconnectIcon.setColorFilter(iconColor);
            mBluetoothEndIcon.setColorFilter(iconColor);

            final Bundle args = new Bundle(1);
            args.putString(KEY_DEVICE_ADDRESS, device.getAddress());
            mBluetoothEndIcon.setOnClickListener(v -> {
                mDialog.startActivity(new Intent(DEVICE_DETAIL_INTENT)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, args), v);
            });
            
        }
    }
}
