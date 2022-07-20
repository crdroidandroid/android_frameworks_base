package com.google.android.systemui.smartspace.logging;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.uitemplatedata.BaseTemplateData;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import com.google.android.systemui.smartspace.InstanceId;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardMetadataLoggingInfo;
import com.google.android.systemui.smartspace.logging.BcSmartspaceSubcardLoggingInfo;
import java.util.ArrayList;
import java.util.List;

public final class BcSmartspaceCardLoggerUtil {
    public static BcSmartspaceSubcardLoggingInfo createSubcardLoggingInfo(SmartspaceTarget smartspaceTarget) {
        if (smartspaceTarget == null || smartspaceTarget.getBaseAction() == null || smartspaceTarget.getBaseAction().getExtras() == null || smartspaceTarget.getBaseAction().getExtras().isEmpty() || smartspaceTarget.getBaseAction().getExtras().getInt("subcardType", -1) == -1) {
            return null;
        }
        SmartspaceAction baseAction = smartspaceTarget.getBaseAction();
        int create = InstanceId.create(baseAction.getExtras().getString("subcardId"));
        int i = baseAction.getExtras().getInt("subcardType");
        BcSmartspaceCardMetadataLoggingInfo.Builder builder = new BcSmartspaceCardMetadataLoggingInfo.Builder();
        builder.mInstanceId = create;
        builder.mCardTypeId = i;
        BcSmartspaceCardMetadataLoggingInfo bcSmartspaceCardMetadataLoggingInfo = new BcSmartspaceCardMetadataLoggingInfo(builder);
        ArrayList arrayList = new ArrayList();
        arrayList.add(bcSmartspaceCardMetadataLoggingInfo);
        BcSmartspaceSubcardLoggingInfo.Builder builder2 = new BcSmartspaceSubcardLoggingInfo.Builder();
        builder2.mSubcards = arrayList;
        builder2.mClickedSubcardIndex = 0;
        return new BcSmartspaceSubcardLoggingInfo(builder2);
    }

    public static int getUid(PackageManager packageManager, SmartspaceTarget smartspaceTarget) {
        int i = -1;
        if (packageManager != null) {
            i = -1;
            if (smartspaceTarget != null) {
                i = -1;
                if (smartspaceTarget.getComponentName() != null) {
                    i = -1;
                    if (!TextUtils.isEmpty(smartspaceTarget.getComponentName().getPackageName())) {
                        if ("package_name".equals(smartspaceTarget.getComponentName().getPackageName())) {
                            i = -1;
                        } else {
                            try {
                                ApplicationInfo applicationInfo = packageManager.getApplicationInfo(smartspaceTarget.getComponentName().getPackageName(), PackageManager.ApplicationInfoFlags.of(0L));
                                i = -1;
                                if (applicationInfo != null) {
                                    i = applicationInfo.uid;
                                }
                            } catch (PackageManager.NameNotFoundException e) {
                                i = -1;
                            }
                        }
                    }
                }
            }
        }
        return i;
    }

    public static void createSubcardLoggingInfoHelper(ArrayList arrayList, BaseTemplateData.SubItemInfo subItemInfo) {
        if (subItemInfo != null && subItemInfo.getLoggingInfo() != null) {
            BaseTemplateData.SubItemLoggingInfo loggingInfo = subItemInfo.getLoggingInfo();
            BcSmartspaceCardMetadataLoggingInfo.Builder builder = new BcSmartspaceCardMetadataLoggingInfo.Builder();
            builder.mCardTypeId = loggingInfo.getFeatureType();
            builder.mInstanceId = loggingInfo.getInstanceId();
            arrayList.add(new BcSmartspaceCardMetadataLoggingInfo(builder));
        }
    }

    public static boolean tryForcePrimaryFeatureType(BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo) {
        if (bcSmartspaceCardLoggingInfo.mFeatureType != 1) {
            return false;
        }
        bcSmartspaceCardLoggingInfo.mFeatureType = 39;
        bcSmartspaceCardLoggingInfo.mInstanceId = InstanceId.create("date_card_794317_92634");
        return true;
    }

    public static void tryForcePrimaryFeatureTypeAndInjectWeatherSubcard(BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo, SmartspaceTarget smartspaceTarget) {
        if (tryForcePrimaryFeatureType(bcSmartspaceCardLoggingInfo) && smartspaceTarget != null && !"date_card_794317_92634".equals(smartspaceTarget.getSmartspaceTargetId())) {
            if (bcSmartspaceCardLoggingInfo.mSubcardInfo == null) {
                BcSmartspaceSubcardLoggingInfo.Builder builder = new BcSmartspaceSubcardLoggingInfo.Builder();
                builder.mClickedSubcardIndex = 0;
                builder.mSubcards = new ArrayList();
                bcSmartspaceCardLoggingInfo.mSubcardInfo = new BcSmartspaceSubcardLoggingInfo(builder);
            }
            BcSmartspaceSubcardLoggingInfo bcSmartspaceSubcardLoggingInfo = bcSmartspaceCardLoggingInfo.mSubcardInfo;
            if (bcSmartspaceSubcardLoggingInfo.mSubcards == null) {
                bcSmartspaceSubcardLoggingInfo.mSubcards = new ArrayList();
            }
            if (bcSmartspaceCardLoggingInfo.mSubcardInfo.mSubcards.size() == 0 || (bcSmartspaceCardLoggingInfo.mSubcardInfo.mSubcards.get(0) != null && bcSmartspaceCardLoggingInfo.mSubcardInfo.mSubcards.get(0).mCardTypeId != 1)) {
                List<BcSmartspaceCardMetadataLoggingInfo> list = bcSmartspaceCardLoggingInfo.mSubcardInfo.mSubcards;
                BcSmartspaceCardMetadataLoggingInfo.Builder builder2 = new BcSmartspaceCardMetadataLoggingInfo.Builder();
                builder2.mInstanceId = InstanceId.create(smartspaceTarget);
                builder2.mCardTypeId = 1;
                list.add(0, new BcSmartspaceCardMetadataLoggingInfo(builder2));
                BcSmartspaceSubcardLoggingInfo bcSmartspaceSubcardLoggingInfo2 = bcSmartspaceCardLoggingInfo.mSubcardInfo;
                int i = bcSmartspaceSubcardLoggingInfo2.mClickedSubcardIndex;
                if (i > 0) {
                    bcSmartspaceSubcardLoggingInfo2.mClickedSubcardIndex = i + 1;
                }
            }
        }
    }

    public static BcSmartspaceSubcardLoggingInfo createSubcardLoggingInfo(BaseTemplateData baseTemplateData) {
        if (baseTemplateData == null) {
            return null;
        }
        ArrayList arrayList = new ArrayList();
        createSubcardLoggingInfoHelper(arrayList, baseTemplateData.getSubtitleItem());
        createSubcardLoggingInfoHelper(arrayList, baseTemplateData.getSubtitleSupplementalItem());
        createSubcardLoggingInfoHelper(arrayList, baseTemplateData.getSupplementalLineItem());
        if (arrayList.isEmpty()) {
            return null;
        }
        BcSmartspaceSubcardLoggingInfo.Builder builder = new BcSmartspaceSubcardLoggingInfo.Builder();
        builder.mSubcards = arrayList;
        return new BcSmartspaceSubcardLoggingInfo(builder);
    }
}
