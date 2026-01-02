package com.google.android.systemui.smartspace.uitemplate;

import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.uitemplatedata.Icon;
import android.app.smartspace.uitemplatedata.SubImageTemplateData;
import android.app.smartspace.uitemplatedata.TapAction;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon.OnDrawableLoadedListener;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import com.google.android.systemui.smartspace.BcSmartSpaceUtil;
import com.google.android.systemui.smartspace.BcSmartspaceCardSecondary;
import com.google.android.systemui.smartspace.BcSmartspaceTemplateDataUtils;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggerUtil;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

import com.android.systemui.res.R;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.VarHandle;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class SubImageTemplateCard extends BcSmartspaceCardSecondary {
    private static final String TAG = "SubImageTemplateCard";
    
    public final Handler mHandler;
    public final Map<String, Drawable> mIconDrawableCache;
    public final int mImageHeight;
    public ImageView mImageView;

    public SubImageTemplateCard(Context context) {
        this(context, null);
    }

    public SubImageTemplateCard(Context context, AttributeSet attrs) {
        super(context, attrs);
        mIconDrawableCache = new HashMap<>();
        mHandler = new Handler();
        mImageHeight = getResources().getDimensionPixelOffset(R.dimen.enhanced_smartspace_card_height);
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        mImageView = findViewById(R.id.image_view);
    }

    @Override
    public final void resetUi() {
        if (mIconDrawableCache != null) {
            mIconDrawableCache.clear();
        }
        if (mImageView != null) {
            mImageView.getLayoutParams().width = ViewGroup.LayoutParams.WRAP_CONTENT;
            mImageView.setImageDrawable(null);
            mImageView.setBackgroundTintList(null);
        }
    }

    @Override
    public final void setTextColor(int color) {
    }

    @Override
    public final boolean setSmartspaceActions(SmartspaceTarget target, 
                                              BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier, 
                                              BcSmartspaceCardLoggingInfo loggingInfo) {
        
        SubImageTemplateData templateData = (SubImageTemplateData) target.getTemplateData();
        if (!BcSmartspaceCardLoggerUtil.containsValidTemplateType(templateData) || 
            templateData.getSubImages() == null || templateData.getSubImages().isEmpty()) {
            Log.w(TAG, "SubImageTemplateData is null or has no SubImage or invalid template type");
            return false;
        }

        List<Icon> subImages = templateData.getSubImages();
        TapAction tapAction = templateData.getSubImageAction();

        if (mImageView == null) {
            Log.w(TAG, "No image view can be updated. Skipping background update...");
        } else if (tapAction != null && tapAction.getExtras() != null) {
            Bundle extras = tapAction.getExtras();
            String dimensionRatio = extras.getString("imageDimensionRatio", "");
            if (!TextUtils.isEmpty(dimensionRatio)) {
                mImageView.getLayoutParams().width = 0;
                ((ConstraintLayout.LayoutParams) mImageView.getLayoutParams()).dimensionRatio = dimensionRatio;
            }
            if (extras.getBoolean("shouldShowBackground", false)) {
                mImageView.setBackgroundTintList(ColorStateList.valueOf(
                        getContext().getColor(R.color.smartspace_button_background)));
            }
        }

        int frameDurationMillis = (tapAction != null && tapAction.getExtras() != null) 
                    ? tapAction.getExtras().getInt("GifFrameDurationMillis", 200) 
                    : 200;

        ContentResolver contentResolver = getContext().getApplicationContext().getContentResolver();
        TreeMap<Integer, Drawable> frameMap = new TreeMap<>();
        WeakReference<ImageView> imageViewRef = new WeakReference<>(mImageView);
        String prevTargetId = mPrevSmartspaceTargetId;

        for (int i = 0; i < subImages.size(); i++) {
            int index = i;
            Icon subImage = subImages.get(i);
            if (subImage == null || subImage.getIcon() == null) continue;

            android.graphics.drawable.Icon icon = subImage.getIcon();
            String cacheKey = getCacheKey(icon);

            OnDrawableLoadedListener listener = drawable -> {
                if (!prevTargetId.equals(mPrevSmartspaceTargetId)) {
                    Log.d(TAG, "SmartspaceTarget has changed. Skip the loaded result...");
                    return;
                }

                if (drawable != null) {
                    mIconDrawableCache.put(cacheKey, drawable);
                    frameMap.put(index, drawable);
                }

                if (frameMap.size() == subImages.size()) {
                    createAndStartAnimation(frameMap, frameDurationMillis, imageViewRef);
                }
            };

            if (mIconDrawableCache.containsKey(cacheKey)) {
                listener.onDrawableLoaded(mIconDrawableCache.get(cacheKey));
            } else if (icon.getType() == android.graphics.drawable.Icon.TYPE_URI) {
                DrawableWrapper wrapper = new DrawableWrapper();
                wrapper.mUri = icon.getUri();
                wrapper.mHeightInPx = mImageHeight;
                wrapper.mContentResolver = contentResolver;
                wrapper.mListener = listener;
                new LoadUriTask().execute(wrapper);
            } else {
                icon.loadDrawableAsync(getContext(), listener, mHandler);
            }
        }

        if (tapAction != null) {
            BcSmartSpaceUtil.setOnClickListener(this, target, tapAction, 
                    eventNotifier, TAG, loggingInfo, 0);
        }

        return true;
    }

    private String getCacheKey(android.graphics.drawable.Icon icon) {
        StringBuilder keyBuilder = new StringBuilder().append(icon.getType());
        switch (icon.getType()) {
            case android.graphics.drawable.Icon.TYPE_BITMAP:
            case android.graphics.drawable.Icon.TYPE_ADAPTIVE_BITMAP:
                keyBuilder.append(icon.getBitmap().hashCode());
                break;
            case android.graphics.drawable.Icon.TYPE_RESOURCE:
                keyBuilder.append(icon.getResPackage()).append(String.format("0x%08x", icon.getResId()));
                break;
            case android.graphics.drawable.Icon.TYPE_DATA:
                keyBuilder.append(Arrays.hashCode(icon.getDataBytes()));
                break;
            case android.graphics.drawable.Icon.TYPE_URI:
            case android.graphics.drawable.Icon.TYPE_URI_ADAPTIVE_BITMAP:
                keyBuilder.append(icon.getUriString());
                break;
        }
        return keyBuilder.toString();
    }

    private void createAndStartAnimation(TreeMap<Integer, Drawable> drawables, int duration, WeakReference<ImageView> imageViewRef) {
        List<Drawable> validDrawables = drawables.values().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        ImageView iv = imageViewRef.get();
        if (validDrawables.isEmpty()) {
            Log.w(TAG, "All images failed to load. Resetting imageView");
            if (iv != null) {
                iv.getLayoutParams().width = -2;
                iv.setImageDrawable(null);
                iv.setBackgroundTintList(null);
            }
            return;
        }

        AnimationDrawable animationDrawable = new AnimationDrawable();
        for (Drawable d : validDrawables) {
            animationDrawable.addFrame(d, duration);
        }

        if (iv != null) {
            iv.setImageDrawable(animationDrawable);
            int intrinsicWidth = animationDrawable.getIntrinsicWidth();
            if (iv.getLayoutParams().width != intrinsicWidth) {
                iv.getLayoutParams().width = intrinsicWidth;
                iv.requestLayout();
            }
            animationDrawable.start();
        }
    }

    private static final class DrawableWrapper {
        ContentResolver mContentResolver;
        Drawable mDrawable;
        int mHeightInPx;
        OnDrawableLoadedListener mListener;
        Uri mUri;
    }

    private static final class LoadUriTask extends AsyncTask<DrawableWrapper, Void, DrawableWrapper> {
        @Override
        protected DrawableWrapper doInBackground(DrawableWrapper... wrappers) {
            if (wrappers.length == 0) return null;
            DrawableWrapper wrapper = wrappers[0];
            try (InputStream inputStream = wrapper.mContentResolver.openInputStream(wrapper.mUri)) {
                ImageDecoder.Source source = ImageDecoder.createSource(null, inputStream);
                
                wrapper.mDrawable = ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int height = info.getSize().getHeight();
                    float ratio = height != 0 ? (float) info.getSize().getWidth() / height : 0.0f;
                    decoder.setTargetSize((int) (wrapper.mHeightInPx * ratio), wrapper.mHeightInPx);
                });
            } catch (Exception e) {
                Log.w(TAG, "Failed to load uri: " + wrapper.mUri, e);
            }
            return wrapper;
        }

        @Override
        protected void onPostExecute(DrawableWrapper wrapper) {
            if (wrapper != null && wrapper.mListener != null) {
                wrapper.mListener.onDrawableLoaded(wrapper.mDrawable);
            }
        }
    }
}
