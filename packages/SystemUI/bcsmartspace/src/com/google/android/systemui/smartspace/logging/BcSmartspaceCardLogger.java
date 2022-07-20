package com.google.android.systemui.smartspace.logging;

import android.os.Debug;
import android.util.Log;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.smartspace.nano.SmartspaceProto;
import com.google.android.systemui.smartspace.EventEnum;
import com.google.protobuf.nano.MessageNano;
import java.util.ArrayList;
import java.util.List;

public final class BcSmartspaceCardLogger {
    public static final String TAG = "StatsLog";
    public static final boolean IS_VERBOSE = Log.isLoggable(TAG, 2);

    public static void log(EventEnum eventEnum, BcSmartspaceCardLoggingInfo cardInfo) {
        byte[] subcardList;
        BcSmartspaceSubcardLoggingInfo subcardInfo = cardInfo.mSubcardInfo;
        if (subcardInfo != null && subcardInfo.mSubcards != null && !subcardInfo.mSubcards.isEmpty()) {
            SmartspaceProto.SmartSpaceSubcards subcards = new SmartspaceProto.SmartSpaceSubcards();
            subcards.clickedSubcardIndex = subcardInfo.mClickedSubcardIndex;
            List<BcSmartspaceCardMetadataLoggingInfo> metadataLogging = subcardInfo.mSubcards;
            ArrayList<SmartspaceProto.SmartSpaceCardMetadata> metadata = new ArrayList<>();
            metadataLogging.forEach(metaDataLoggingList -> {
                SmartspaceProto.SmartSpaceCardMetadata cardMetadata = new SmartspaceProto.SmartSpaceCardMetadata();
                cardMetadata.instanceId = metaDataLoggingList.mInstanceId;
                cardMetadata.cardTypeId = metaDataLoggingList.mCardTypeId;
                metadata.add(cardMetadata);
            });
            subcards.subcards = (SmartspaceProto.SmartSpaceCardMetadata[]) metadata.toArray(new SmartspaceProto.SmartSpaceCardMetadata[0]);
            subcardList = MessageNano.toByteArray(subcards);
        } else {
            subcardList = null;
        }
        writeLog(eventEnum, cardInfo, subcardList);
    }

    static void writeLog(EventEnum eventEnum, BcSmartspaceCardLoggingInfo cardInfo, byte[] subcards) {
        SysUiStatsLog.write(352, eventEnum.getId(), cardInfo.mInstanceId, 0, cardInfo.mDisplaySurface, cardInfo.mRank, cardInfo.mCardinality, cardInfo.mFeatureType, cardInfo.mUid, 0, 0, cardInfo.mReceivedLatency, subcards, subcards);
        if (IS_VERBOSE) {
            Log.d(TAG, String.format("\nLogged Smartspace event(%s), info(%s), callers=%s", eventEnum, cardInfo.toString(), Debug.getCallers(5)));
        }
    }
}
