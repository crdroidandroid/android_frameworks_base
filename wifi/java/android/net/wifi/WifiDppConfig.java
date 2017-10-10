/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.wifi;
import android.os.Parcel;
import android.os.Parcelable;

/** DPP configuration class
 * @hide
 */
public class WifiDppConfig implements Parcelable {
    private static final String TAG = "WifiDppConfig";
    public static final int DPP_INVALID_CONFIG_ID = -1;
    public static final int DPP_ROLE_CONFIGURATOR = 0;
    public static final int DPP_ROLE_ENROLLEE = 1;
    public static final int DPP_TYPE_QR_CODE = 0;
    public static final int DPP_TYPE_NAN_BOOTSTRAP = 1;
    public int peer_bootstrap_id;
    public int own_bootstrap_id;
    public int dpp_role;
    public String ssid;
    public String passphrase;
    public int isAp;
    public int isDpp;
    public int conf_id;
    public int bootstrap_type;
    public String chan_list;
    public String mac_addr;
    public String info;
    public String curve;
    public int expiry;
    public String key;

    private DppResult mEventResult = new DppResult();

    public WifiDppConfig() {
        peer_bootstrap_id = DPP_INVALID_CONFIG_ID;
        own_bootstrap_id = DPP_INVALID_CONFIG_ID;
        dpp_role = DPP_INVALID_CONFIG_ID;
        isAp = DPP_INVALID_CONFIG_ID;
        isDpp = DPP_INVALID_CONFIG_ID;
        conf_id = DPP_INVALID_CONFIG_ID;
        bootstrap_type = DPP_INVALID_CONFIG_ID;
        expiry = 0;
        ssid = null;
        passphrase = null;
        chan_list = null;
        mac_addr = null;
        info = null;
        curve = null;
        key = null;
    }

    public DppResult getDppResult() {
        return mEventResult;
    }

    public void setDppResult(DppResult result) {
        mEventResult = result;
    }

    public void writeToParcel(Parcel dest) {
    }

    public void readFromParcel(Parcel in) {
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }
    /** Implement the Parcelable interface {@hide} */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(peer_bootstrap_id);
        dest.writeInt(own_bootstrap_id);
        dest.writeInt(dpp_role);
        dest.writeString(ssid);
        dest.writeString(passphrase);
        dest.writeInt(isAp);
        dest.writeInt(isDpp);
        dest.writeInt(conf_id);
        dest.writeInt(bootstrap_type);
        dest.writeString(chan_list);
        dest.writeString(mac_addr);
        dest.writeString(info);
        dest.writeString(curve);
        dest.writeInt(expiry);
        dest.writeString(key);
        mEventResult.writeToParcel(dest);
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<WifiDppConfig> CREATOR =
        new Creator<WifiDppConfig>() {
            public WifiDppConfig createFromParcel(Parcel in) {
                WifiDppConfig config = new WifiDppConfig();
                config.peer_bootstrap_id = in.readInt();
                config.own_bootstrap_id = in.readInt();
                config.dpp_role = in.readInt();
                config.ssid = in.readString();
                config.passphrase = in.readString();
                config.isAp = in.readInt();
                config.isDpp = in.readInt();
                config.conf_id = in.readInt();
                config.bootstrap_type = in.readInt();
                config.chan_list = in.readString();
                config.mac_addr = in.readString();
                config.info = in.readString();
                config.curve = in.readString();
                config.expiry = in.readInt();
                config.key = in.readString();
                config.mEventResult.readFromParcel(in);
                return config;
            }
            public WifiDppConfig[] newArray(int size) {
                return new WifiDppConfig[size];
            }
        };
    /**
     * Stores supplicant state change information passed from WifiMonitor to
     * a state machine. WifiStateMachine, SupplicantStateTracker and WpsStateMachine
     * are example state machines that handle it.
     * @hide
     */
    public static class DppResult {

        public boolean initiator;
        public int netID;
        public byte capab;
        public byte authMissingParam;
        public byte configEventType;
        public String iBootstrapData;
        public String ssid;
        public String connector;
        public String cSignKey;
        public String netAccessKey;
        public int netAccessKeyExpiry;
        public String passphrase;
        public String psk;

        public static final int DPP_EVENT_AUTH_SUCCESS        = 0;
        public static final int DPP_EVENT_NOT_COMPATIBLE      = 1;
        public static final int DPP_EVENT_RESPONSE_PENDING    = 2;
        public static final int DPP_EVENT_SCAN_PEER_QRCODE    = 3;
        public static final int DPP_EVENT_CONF                = 4;
        public static final int DPP_EVENT_MISSING_AUTH        = 5;
        public static final int DPP_EVENT_NETWORK_ID          = 6;


        public static final int DPP_CONF_EVENT_TYPE_FAILED    = 0;
        public static final int DPP_CONF_EVENT_TYPE_SENT      = 1;
        public static final int DPP_CONF_EVENT_TYPE_RECEIVED  = 2;

        public DppResult() {
            this.initiator = false;
            this.netID = -1;
            this.capab = 0;
            this.authMissingParam = 0;
            this.configEventType = 0;
            this.iBootstrapData = null;
            this.ssid = null;
            this.connector = null;
            this.cSignKey = null;
            this.netAccessKey = null;
            this.netAccessKeyExpiry = 0;
            this.passphrase = null;
            this.psk = null;
        }

        public void writeToParcel(Parcel dest) {
            dest.writeInt(initiator ? 1 : 0);
            dest.writeInt(netID);
            dest.writeByte(capab);
            dest.writeByte(authMissingParam);
            dest.writeByte(configEventType);
            dest.writeString(iBootstrapData);
            dest.writeString(ssid);
            dest.writeString(connector);
            dest.writeString(cSignKey);
            dest.writeString(netAccessKey);
            dest.writeInt(netAccessKeyExpiry);
            dest.writeString(passphrase);
            dest.writeString(psk);
        }

        public void readFromParcel(Parcel in) {
            this.initiator = (in.readInt() > 0) ? true : false;
            this.netID = in.readInt();
            this.capab = in.readByte();
            this.authMissingParam = in.readByte();
            this.configEventType = in.readByte();
            this.iBootstrapData = in.readString();
            this.ssid = in.readString();
            this.connector = in.readString();
            this.cSignKey = in.readString();
            this.netAccessKey = in.readString();
            this.netAccessKeyExpiry = in.readInt();
            this.passphrase = in.readString();
            this.psk = in.readString();
        }
    }
}
