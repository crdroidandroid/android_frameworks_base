/*
 * Copyright (C) 2019 AquariOS
 *               2019 crDroid Android Project
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
 *
 * Use FontService API to show, preview, and apply fonts 
 */

package com.android.systemui.qs.tiles;

import android.content.Context;
import android.content.FontInfo;
import android.content.IFontService;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSDetailItemsList;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QSDetailItems.Item;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.tileimpl.QSTileImpl.ResourceIcon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class FontTile extends QSTileImpl<BooleanState> {
    private static final String TAG = "FontTile";

    public FontTile(QSHost host) {
        super(host);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        showDetail(true);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = mContext.getString(R.string.quick_settings_font_label);
        state.icon = ResourceIcon.get(R.drawable.ic_qs_font);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.CRDROID_SETTINGS;
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    protected void handleSetListening(boolean listening) {
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_font_label);
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return new FontDetailAdapter();
    }

    private class FontDetailAdapter
            implements DetailAdapter, AdapterView.OnItemClickListener {
        private QSDetailItemsList mItemsList;
        private QSDetailItemsList.QSDetailListAdapter mAdapter;
        private List<Item> mFontItems = new ArrayList<>();

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            Item item = (Item) parent.getItemAtPosition(position);
            if (item == null || item.tag == null)
                return;
            final FontInfo fontInfo = (FontInfo) item.tag;
            showDetail(false);
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        IFontService fontService = IFontService.Stub
                                .asInterface(ServiceManager.getService("fontservice"));
                        fontService.applyFont(fontInfo);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error in changing font");
                    }
                }
            }, 500);
        }

        @Override
        public CharSequence getTitle() {
            return mContext.getString(R.string.quick_settings_font_detail_label);
        }

        @Override
        public Boolean getToggleState() {
            return null;
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            mItemsList = QSDetailItemsList.convertOrInflate(context, convertView, parent);
            mAdapter = new QSDetailItemsList.QSDetailListAdapter(context, mFontItems);
            ListView listView = mItemsList.getListView();
            listView.setDivider(null);
            listView.setOnItemClickListener(this);
            listView.setAdapter(mAdapter);
            updateItems();
            return mItemsList;
        }

        void updateItems() {
            List<FontInfo> fontInfoList = new ArrayList<FontInfo>();
            try {
                IFontService fontService = IFontService.Stub
                        .asInterface(ServiceManager.getService("fontservice"));
                Map<String, List<FontInfo>> fontMap = fontService.getAllFonts();
                for (Map.Entry<String, List<FontInfo>> entry : fontMap.entrySet()) {
                    String packageName = entry.getKey();
                    List<FontInfo> fonts = entry.getValue();
                    // manually add system font after we sort
                    if (TextUtils.equals(packageName, FontInfo.DEFAULT_FONT_PACKAGE)) {
                        continue;
                    }
                    for (FontInfo font : fonts) {
                        fontInfoList.add(new FontInfo(font));
                    }
                }
                Collections.sort(fontInfoList);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in populating list");
            }
            fontInfoList.add(0, FontInfo.getDefaultFontInfo());

            // convert FontInfo to FontItem
            mFontItems.clear();
            for (FontInfo fontInfo : fontInfoList) {
                Item item = new Item();
                item.tag = fontInfo;
                item.doDisableTint = true;
                item.doDisableFocus = true;
                item.fontPath = fontInfo.previewPath;
                item.icon = getIcon(fontInfo.packageName);
                item.line1 = fontInfo.fontName.replace("_", " ");
                item.line2 = mContext.getString(R.string.quick_settings_font_pangram);
                mFontItems.add(item);
            }
            mAdapter.notifyDataSetChanged();
        }

        @Override
        public Intent getSettingsIntent() {
            return new Intent(Settings.ACTION_DISPLAY_SETTINGS);
        }

        @Override
        public void setToggleState(boolean state) {
        }

        @Override
        public int getMetricsCategory() {
            return MetricsEvent.CRDROID_SETTINGS;
        }
    }

    private Drawable getPackageDrawable(String packageName) {
        Drawable icon = null;
        try {
            ApplicationInfo info = mContext.getPackageManager().getApplicationInfo(packageName, 0);
            icon = info.loadIcon(mContext.getPackageManager());
        } finally {
            return icon;
        }
    }

    private QSTile.Icon getIcon(String _packageName) {
        final String packageName = _packageName;
        QSTile.Icon icon = new QSTile.Icon() {
            @Override
            public Drawable getDrawable(Context context) {
                return getPackageDrawable(packageName);
            }
        };
        return icon;
    }
}
