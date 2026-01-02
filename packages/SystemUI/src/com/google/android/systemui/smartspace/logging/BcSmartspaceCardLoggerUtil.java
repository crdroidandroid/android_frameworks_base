package com.google.android.systemui.smartspace.logging;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.uitemplatedata.BaseTemplateData;
import android.os.Bundle;

import com.android.systemui.smartspace.nano.SmartspaceProto;

import com.google.android.systemui.smartspace.InstanceId;

import java.util.ArrayList;
import java.util.List;

public abstract class BcSmartspaceCardLoggerUtil {
    public static boolean containsValidTemplateType(BaseTemplateData data) {
        return (data == null || data.getTemplateType() == 0 || data.getTemplateType() == 8) ? false : true;
    }

    public static SmartspaceProto.SmartspaceCardDimensionalInfo createDimensionalLoggingInfo(BaseTemplateData data) {
        if (data == null || data.getPrimaryItem() == null || data.getPrimaryItem().getTapAction() == null) {
            return null;
        }

        Bundle extras = data.getPrimaryItem().getTapAction().getExtras();
        List<SmartspaceProto.SmartspaceFeatureDimension> dimensions = new ArrayList<>();

        if (extras != null && !extras.isEmpty()) {
            ArrayList<Integer> ids = extras.getIntegerArrayList("ss_card_dimension_ids");
            ArrayList<Integer> values = extras.getIntegerArrayList("ss_card_dimension_values");
            if (ids != null && values != null && ids.size() == values.size()) {
                for (int i = 0; i < ids.size(); i++) {
                    SmartspaceProto.SmartspaceFeatureDimension dimension = new SmartspaceProto.SmartspaceFeatureDimension();
                    dimension.featureDimensionId = ids.get(i);
                    dimension.featureDimensionValue = values.get(i);
                    dimensions.add(dimension);
                }
            }
        }

        if (dimensions.isEmpty()) {
            return null;
        }

        SmartspaceProto.SmartspaceCardDimensionalInfo info = new SmartspaceProto.SmartspaceCardDimensionalInfo();
        info.featureDimensions = dimensions.toArray(new SmartspaceProto.SmartspaceFeatureDimension[dimensions.size()]);
        return info;
    }

    public static BcSmartspaceSubcardLoggingInfo createSubcardLoggingInfo(SmartspaceTarget target) {
        if (target.getBaseAction() == null || target.getBaseAction().getExtras() == null || target.getBaseAction().getExtras().isEmpty() || target.getBaseAction().getExtras().getInt("subcardType", -1) == -1) {
            return null;
        }

        int instanceId = InstanceId.create(target.getBaseAction().getExtras().getString("subcardId"));
        int cardTypeId = target.getBaseAction().getExtras().getInt("subcardType");

        BcSmartspaceCardMetadataLoggingInfo.Builder builder =
                new BcSmartspaceCardMetadataLoggingInfo.Builder();
        builder.mInstanceId = instanceId;
        builder.mCardTypeId = cardTypeId;

        BcSmartspaceCardMetadataLoggingInfo metadata =
                new BcSmartspaceCardMetadataLoggingInfo(builder);
        List<BcSmartspaceCardMetadataLoggingInfo> subcards = new ArrayList<>();
        subcards.add(metadata);

        BcSmartspaceSubcardLoggingInfo subcardInfo = new BcSmartspaceSubcardLoggingInfo();
        subcardInfo.mSubcards = subcards;
        subcardInfo.mClickedSubcardIndex = 0;
        return subcardInfo;
    }

    public static void createSubcardLoggingInfoHelper(
            List<BcSmartspaceCardMetadataLoggingInfo> subcards,
            BaseTemplateData.SubItemInfo subItemInfo) {
        if (subItemInfo != null && subItemInfo.getLoggingInfo() != null) {
            BaseTemplateData.SubItemLoggingInfo loggingInfo = subItemInfo.getLoggingInfo();
            BcSmartspaceCardMetadataLoggingInfo.Builder builder =
                    new BcSmartspaceCardMetadataLoggingInfo.Builder();
            builder.mCardTypeId = loggingInfo.getFeatureType();
            builder.mInstanceId = loggingInfo.getInstanceId();
            subcards.add(new BcSmartspaceCardMetadataLoggingInfo(builder));
        }
    }

    public static void tryForcePrimaryFeatureTypeOrUpdateLogInfoFromTemplateData(BcSmartspaceCardLoggingInfo loggingInfo, BaseTemplateData data) {
        if (loggingInfo.mFeatureType == 1) {
            loggingInfo.mFeatureType = 39;
            loggingInfo.mInstanceId = InstanceId.create("date_card_794317_92634");
            return;
        }
        if (data == null || data.getPrimaryItem() == null || data.getPrimaryItem().getLoggingInfo() == null) {
            return;
        }
        int featureType = data.getPrimaryItem().getLoggingInfo().getFeatureType();
        if (featureType > 0) {
            loggingInfo.mFeatureType = featureType;
        }
        int instanceId = data.getPrimaryItem().getLoggingInfo().getInstanceId();
        if (instanceId > 0) {
            loggingInfo.mInstanceId = instanceId;
        }
    }

    public static BcSmartspaceSubcardLoggingInfo createSubcardLoggingInfo(BaseTemplateData data) {
        if (data == null) {
            return null;
        }

        List<BcSmartspaceCardMetadataLoggingInfo> subcards = new ArrayList<>();

        if (data.getPrimaryItem() != null && data.getPrimaryItem().getLoggingInfo() != null && data.getPrimaryItem().getLoggingInfo().getFeatureType() == 1) {
            createSubcardLoggingInfoHelper(subcards, data.getPrimaryItem());
        }

        createSubcardLoggingInfoHelper(subcards, data.getSubtitleItem());
        createSubcardLoggingInfoHelper(subcards, data.getSubtitleSupplementalItem());
        createSubcardLoggingInfoHelper(subcards, data.getSupplementalLineItem());

        if (subcards.isEmpty()) {
            return null;
        }

        BcSmartspaceSubcardLoggingInfo subcardInfo = new BcSmartspaceSubcardLoggingInfo();
        subcardInfo.mSubcards = subcards;
        subcardInfo.mClickedSubcardIndex = 0;
        return subcardInfo;
    }
}
