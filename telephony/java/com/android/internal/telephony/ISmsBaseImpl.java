/*
 * Copyright (c) 2018, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *   * Neither the name of The Linux Foundation nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.internal.telephony;

import android.app.PendingIntent;
import android.net.Uri;
import android.os.RemoteException;
import java.util.List;

public class ISmsBaseImpl extends ISms.Stub {

    @Override
    public List<SmsRawData> getAllMessagesFromIccEfForSubscriber(int subId, String callingPkg)
            throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public boolean updateMessageOnIccEfForSubscriber(int subId, String callingPkg,
             int messageIndex, int newStatus, byte[] pdu) throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public boolean copyMessageToIccEfForSubscriber(int subId, String callingPkg, int status,
            byte[] pdu, byte[] smsc) throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public void sendDataForSubscriber(int subId, String callingPkg, String destAddr,
            String scAddr, int destPort, byte[] data, PendingIntent sentIntent,
            PendingIntent deliveryIntent) throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public void sendDataForSubscriberWithSelfPermissions(int subId, String callingPkg,
            String destAddr, String scAddr, int destPort, byte[] data,
            PendingIntent sentIntent, PendingIntent deliveryIntent) throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public void sendTextForSubscriber(int subId, String callingPkg, String destAddr,
            String scAddr, String text, PendingIntent sentIntent,
            PendingIntent deliveryIntent, boolean persistMessageForNonDefaultSmsApp)
            throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public void sendTextForSubscriberWithSelfPermissions(int subId, String callingPkg,
            String destAddr, String scAddr, String text, PendingIntent sentIntent,
            PendingIntent deliveryIntent, boolean persistMessage) throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public void sendTextForSubscriberWithOptions(int subId, String callingPkg, String destAddr,
            String scAddr, String text, PendingIntent sentIntent,
            PendingIntent deliveryIntent, boolean persistMessageForNonDefaultSmsApp,
            int priority, boolean expectMore, int validityPeriod) throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public void injectSmsPduForSubscriber(
            int subId, byte[] pdu, String format, PendingIntent receivedIntent)
            throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public void sendMultipartTextForSubscriber(int subId, String callingPkg,
            String destinationAddress, String scAddress,
            List<String> parts, List<PendingIntent> sentIntents,
            List<PendingIntent> deliveryIntents, boolean persistMessageForNonDefaultSmsApp)
            throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public void sendMultipartTextForSubscriberWithOptions(int subId, String callingPkg,
            String destinationAddress, String scAddress,
            List<String> parts, List<PendingIntent> sentIntents,
            List<PendingIntent> deliveryIntents, boolean persistMessageForNonDefaultSmsApp,
            int priority, boolean expectMore, int validityPeriod) throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public boolean enableCellBroadcastForSubscriber(int subId, int messageIdentifier, int ranType)
            throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public boolean disableCellBroadcastForSubscriber(int subId, int messageIdentifier, int ranType)
            throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public boolean enableCellBroadcastRangeForSubscriber(int subId, int startMessageId,
            int endMessageId, int ranType) throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public boolean disableCellBroadcastRangeForSubscriber(int subId, int startMessageId,
            int endMessageId, int ranType) throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public int getPremiumSmsPermission(String packageName) throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public int getPremiumSmsPermissionForSubscriber(int subId, String packageName) throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public void setPremiumSmsPermission(String packageName, int permission) throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public void setPremiumSmsPermissionForSubscriber(int subId, String packageName,
            int permission) throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public boolean isImsSmsSupportedForSubscriber(int subId) throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public boolean isSmsSimPickActivityNeeded(int subId) throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public int getPreferredSmsSubscription() throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public String getImsSmsFormatForSubscriber(int subId) throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public boolean isSMSPromptEnabled() throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public void sendStoredText(int subId, String callingPkg, Uri messageUri, String scAddress,
            PendingIntent sentIntent, PendingIntent deliveryIntent)
            throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public void sendStoredMultipartText(int subId, String callingPkg, Uri messageUri,
                String scAddress, List<PendingIntent> sentIntents,
                List<PendingIntent> deliveryIntents) throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public String createAppSpecificSmsToken(int subId, String callingPkg, PendingIntent intent)
            throws android.os.RemoteException {
        throw new RemoteException();
    }

    @Override
    public int getSmsCapacityOnIccForSubscriber(int subId) throws android.os.RemoteException {
        throw new RemoteException();
    }
}
