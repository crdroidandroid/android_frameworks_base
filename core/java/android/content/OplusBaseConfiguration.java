package android.content.res;

import android.annotation.Nullable;
import android.content.res.OplusExtraConfiguration;

public abstract class OplusBaseConfiguration {
    private OplusExtraConfiguration mOplusExtraConfiguration = null;

    @Nullable
    public OplusExtraConfiguration getOplusExtraConfiguration() {
        return this.mOplusExtraConfiguration;
    }
}
