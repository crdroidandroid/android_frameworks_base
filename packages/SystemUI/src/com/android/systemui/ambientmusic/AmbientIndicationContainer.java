package com.android.systemui.ambientmusic;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.os.Handler;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.FrameLayout.LayoutParams;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.AutoReinflateContainer;
import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.StatusBar;

import com.android.systemui.ambientmusic.AmbientIndicationInflateListener;

import java.util.concurrent.TimeUnit;

public class AmbientIndicationContainer extends AutoReinflateContainer implements DozeReceiver {
    public View mAmbientIndication;
    private boolean mDozing;
    private ImageView mIcon;
    private CharSequence mIndication;
    private StatusBar mStatusBar;
    private TextView mText;
    private TextView mTrackLength;
    private Context mContext;
    private MediaMetadata mMediaMetaData;
    private boolean mForcedMediaDoze;
    private Handler mHandler;
    private boolean mScrollingInfo;
    private static final String FONT_FAMILY = "sans-serif-light";

    public AmbientIndicationContainer(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        mContext = context;
    }

    public void hideIndication() {
        showIndication(null);
    }

    public void initializeView(StatusBar statusBar, Handler handler) {
        mStatusBar = statusBar;
        addInflateListener(new AmbientIndicationInflateListener(this));
        mHandler = handler;
    }

    public void updateAmbientIndicationView(View view) {
        mAmbientIndication = findViewById(R.id.ambient_indication);
        mText = (TextView)findViewById(R.id.ambient_indication_text);
        mTrackLength = (TextView)findViewById(R.id.ambient_indication_track_length);
        mIcon = (ImageView)findViewById(R.id.ambient_indication_icon);
        setIndication(mMediaMetaData);
    }

    @Override
    public void setDozing(boolean dozing) {
        mDozing = dozing;
        setVisibility(dozing ? View.VISIBLE : View.INVISIBLE);
        updatePosition();
    }

    private void setTickerMarquee(boolean enable) {
        if (enable) {
            // If scrolling is already in progress, don't retry.
            if (mScrollingInfo) return;
            mScrollingInfo = true;
            mText.setEllipsize(TruncateAt.MARQUEE);
            mText.setMarqueeRepeatLimit(-1);
            mText.setSelected(true);
        } else {
            mText.setEllipsize(null);
            mText.setSelected(false);
            mScrollingInfo = false;
        }
    }

    public void setCleanLayout(boolean force) {
        mForcedMediaDoze = force;
        updatePosition();
    }

    public void updatePosition() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) this.getLayoutParams();
        lp.gravity = mForcedMediaDoze ? Gravity.CENTER : Gravity.BOTTOM;
        this.setLayoutParams(lp);
    }

    public void showIndication(MediaMetadata mediaMetaData) {
        CharSequence charSequence = null;
        CharSequence lengthInfo = null;
        Typeface tf = Typeface.create(FONT_FAMILY, Typeface.NORMAL);

        mMediaMetaData = mediaMetaData;
        if (mMediaMetaData != null) {
            CharSequence artist = mMediaMetaData.getText(mMediaMetaData.METADATA_KEY_ARTIST);
            CharSequence album = mediaMetaData.getText(MediaMetadata.METADATA_KEY_ALBUM);
            CharSequence title = mMediaMetaData.getText(mMediaMetaData.METADATA_KEY_TITLE);
            long duration = mMediaMetaData.getLong(mMediaMetaData.METADATA_KEY_DURATION);
            // Select either album name or artist name with song title - whatever available
            if (album != null) {
                charSequence = album.toString();
            }
            if (artist != null) {
                charSequence = artist.toString();
            }
            if (title != null) {
                if (charSequence != null)
                    charSequence = charSequence + " - " + title.toString();
                else
                    charSequence = title.toString();
            }
            if (duration != 0) {
                lengthInfo = String.format("%02d:%02d",
                        TimeUnit.MILLISECONDS.toMinutes(duration),
                        TimeUnit.MILLISECONDS.toSeconds(duration) -
                        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(duration))
                 );
                 mTrackLength.setText(lengthInfo);
                 mTrackLength.setTypeface(tf);
                 mTrackLength.setVisibility(View.VISIBLE);
            } else {
                 mTrackLength.setVisibility(View.GONE);
            }
        }
        if (charSequence != null && !TextUtils.isEmpty(charSequence)) {
            mText.setText(charSequence);
            mText.setTypeface(tf);
            mText.setVisibility(View.VISIBLE);
            setTickerMarquee(true);
        } else {
            // Set visibility GONE for all text if no charSequence
            setTickerMarquee(false);
            mText.setVisibility(View.GONE);
            mTrackLength.setVisibility(View.GONE);
        }
        // Handle case where charSequence is blank but there is music playback
        if (mMediaMetaData != null) {
            mAmbientIndication.setVisibility(View.VISIBLE);
        } else {
            mAmbientIndication.setVisibility(View.INVISIBLE);
        }
    }

    public void setIndication(MediaMetadata mediaMetaData) {
        if (mediaMetaData == mMediaMetaData) return;
        showIndication(mediaMetaData);
        if (mStatusBar != null && mediaMetaData != null) {
            mStatusBar.triggerAmbientForMedia();
        }
    }
}

