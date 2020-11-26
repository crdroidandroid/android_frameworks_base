package com.google.android.systemui.assist.uihints;

import android.content.ComponentName;
import android.content.Context;
import com.android.internal.app.AssistUtils;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class AssistantPresenceHandler {
    private final AssistUtils mAssistUtils;
    private final Set<AssistantPresenceChangeListener> mAssistantPresenceChangeListeners = new HashSet();
    private boolean mGoogleIsAssistant;

    public interface AssistantPresenceChangeListener {
        void onAssistantPresenceChanged(boolean isGoogleAssistant);
    }

    @Inject
    AssistantPresenceHandler(Context context, AssistUtils assistUtils) {
        mAssistUtils = assistUtils;
    }

    public void registerAssistantPresenceChangeListener(AssistantPresenceChangeListener assistantPresenceChangeListener) {
        mAssistantPresenceChangeListeners.add(assistantPresenceChangeListener);
    }

    public void requestAssistantPresenceUpdate() {
        updateAssistantPresence(fetchIsGoogleAssistant());
    }

    private void updateAssistantPresence(boolean isGoogleAssistant) {
        if (mGoogleIsAssistant != isGoogleAssistant) {
            mGoogleIsAssistant = isGoogleAssistant;
            for (AssistantPresenceChangeListener assistantPresenceChangeListener : mAssistantPresenceChangeListeners) {
                assistantPresenceChangeListener.onAssistantPresenceChanged(mGoogleIsAssistant);
            }
        }
    }

    private boolean fetchIsGoogleAssistant() {
        ComponentName assistComponentForUser = mAssistUtils.getAssistComponentForUser(-2);
        return assistComponentForUser != null && "com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService".equals(assistComponentForUser.flattenToString());
    }
}