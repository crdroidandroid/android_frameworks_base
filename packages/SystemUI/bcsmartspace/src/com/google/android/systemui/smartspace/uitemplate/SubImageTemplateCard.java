package com.google.android.systemui.smartspace.uitemplate;

import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.uitemplatedata.SubImageTemplateData;
import android.app.smartspace.uitemplatedata.TapAction;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Size;
import android.widget.ImageView;
import androidx.constraintlayout.widget.ConstraintLayout;
import com.android.systemui.bcsmartspace.R;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.google.android.systemui.smartspace.BcSmartSpaceUtil;
import com.google.android.systemui.smartspace.BcSmartspaceCardSecondary;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;
import com.google.android.systemui.smartspace.logging.LogBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class SubImageTemplateCard extends BcSmartspaceCardSecondary {
    public static final int clinit = 0;
    public final Handler mHandler;
    public final HashMap mIconDrawableCache;
    public final int mImageHeight;
    public ImageView mImageView;

    public static final class LoadUriTask extends AsyncTask<DrawableWrapper, Void, DrawableWrapper> {
        @Override // android.os.AsyncTask
        public final void onPostExecute(DrawableWrapper drawableWrapper) {
            DrawableWrapper drawableWrapper2 = drawableWrapper;
            drawableWrapper2.mListener.onDrawableLoaded(drawableWrapper2.mDrawable);
        }

        @Override // android.os.AsyncTask
        public final DrawableWrapper doInBackground(DrawableWrapper[] drawableWrapperArr) {
            Drawable drawable;
            DrawableWrapper[] drawableWrapperArr2 = drawableWrapperArr;
            DrawableWrapper drawableWrapper = null;
            if (drawableWrapperArr2.length > 0) {
                DrawableWrapper drawableWrapper2 = drawableWrapperArr2[0];
                try {
                    InputStream openInputStream = drawableWrapper2.mContentResolver.openInputStream(drawableWrapper2.mUri);
                    final int i = drawableWrapper2.mHeightInPx;
                    try {
                        drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource((Resources) null, openInputStream), new ImageDecoder.OnHeaderDecodedListener() { // from class: com.google.android.systemui.smartspace.uitemplate.SubImageTemplateCard$$ExternalSyntheticLambda2
                            @Override // android.graphics.ImageDecoder.OnHeaderDecodedListener
                            public final void onHeaderDecoded(ImageDecoder imageDecoder, ImageDecoder.ImageInfo imageInfo, ImageDecoder.Source source) {
                                float f;
                                imageDecoder.setAllocator(3);
                                Size size = imageInfo.getSize();
                                if (size.getHeight() != 0) {
                                    f = size.getWidth() / size.getHeight();
                                } else {
                                    f = 0.0f;
                                }
                                imageDecoder.setTargetSize((int) (i * f), i);
                            }
                        });
                    } catch (IOException e) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Unable to decode stream: ");
                        sb.append(e);
                        Log.e("SubImageTemplateCard", sb.toString());
                        drawable = null;
                    }
                    drawableWrapper2.mDrawable = drawable;
                } catch (Exception e2) {
                    StringBuilder m = LogBuilder.m("open uri:");
                    m.append(drawableWrapper2.mUri);
                    m.append(" got exception:");
                    m.append(e2);
                    Log.w("SubImageTemplateCard", m.toString());
                }
                drawableWrapper = drawableWrapper2;
            }
            return drawableWrapper;
        }
    }

    public SubImageTemplateCard(Context context) {
        this(context, null);
    }

    @Override // com.google.android.systemui.smartspace.BcSmartspaceCardSecondary
    public final void setTextColor(int i) {
    }

    public static final class DrawableWrapper {
        public final ContentResolver mContentResolver;
        public Drawable mDrawable;
        public final int mHeightInPx;
        public final Icon.OnDrawableLoadedListener mListener;
        public final Uri mUri;

        public DrawableWrapper(Uri uri, ContentResolver contentResolver, int i, Icon.OnDrawableLoadedListener listener) {
            this.mUri = uri;
            this.mHeightInPx = i;
            this.mContentResolver = contentResolver;
            this.mListener = listener;
        }
    }

    public SubImageTemplateCard(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        this.mIconDrawableCache = new HashMap();
        this.mHandler = new Handler();
        this.mImageHeight = getResources().getDimensionPixelOffset(R.dimen.enhanced_smartspace_card_height);
    }

    @Override // com.google.android.systemui.smartspace.BcSmartspaceCardSecondary
    public final void resetUi() {
        HashMap hashMap = this.mIconDrawableCache;
        if (hashMap != null) {
            hashMap.clear();
        }
        ImageView imageView = this.mImageView;
        if (imageView != null) {
            imageView.getLayoutParams().width = -2;
            this.mImageView.setImageDrawable(null);
            this.mImageView.setBackgroundTintList(null);
        }
    }

    @Override // com.google.android.systemui.smartspace.BcSmartspaceCardSecondary
    public final boolean setSmartspaceActions(SmartspaceTarget smartspaceTarget, BcSmartspaceDataPlugin.SmartspaceEventNotifier smartspaceEventNotifier, BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo) {
        String sb;
        SubImageTemplateData templateData = (SubImageTemplateData) smartspaceTarget.getTemplateData();
        if (templateData != null && templateData.getSubImages() != null && !templateData.getSubImages().isEmpty()) {
            final List subImages = templateData.getSubImages();
            TapAction subImageAction = templateData.getSubImageAction();
            if (this.mImageView == null) {
                Log.w("SubImageTemplateCard", "No image view can be updated. Skipping background update...");
            } else if (subImageAction != null && subImageAction.getExtras() != null) {
                Bundle extras = subImageAction.getExtras();
                String string = extras.getString("imageDimensionRatio", "");
                if (!TextUtils.isEmpty(string)) {
                    this.mImageView.getLayoutParams().width = 0;
                    ((ConstraintLayout.LayoutParams) this.mImageView.getLayoutParams()).dimensionRatio = string;
                }
                if (extras.getBoolean("shouldShowBackground", false)) {
                    this.mImageView.setBackgroundTintList(ColorStateList.valueOf(getContext().getColor(R.color.smartspace_button_background)));
                }
            }
            int i = 200;
            if (subImageAction != null) {
                if (subImageAction.getExtras() == null) {
                    i = 200;
                } else {
                    i = subImageAction.getExtras().getInt("GifFrameDurationMillis", 200);
                }
            }
            ContentResolver contentResolver = getContext().getApplicationContext().getContentResolver();
            final TreeMap treeMap = new TreeMap();
            final WeakReference weakReference = new WeakReference(this.mImageView);
            final String str = this.mPrevSmartspaceTargetId;
            for (int i2 = 0; i2 < subImages.size(); i2++) {
                android.app.smartspace.uitemplatedata.Icon icon = (android.app.smartspace.uitemplatedata.Icon) subImages.get(i2);
                if (icon != null && icon.getIcon() != null) {
                    Icon icon2 = icon.getIcon();
                    StringBuilder sb2 = new StringBuilder(icon2.getType());
                    switch (icon2.getType()) {
                        case 1:
                        case 5:
                            sb2.append(icon2.getBitmap().hashCode());
                            sb = sb2.toString();
                            break;
                        case 2:
                            sb2.append(icon2.getResPackage());
                            sb2.append(String.format("0x%08x", Integer.valueOf(icon2.getResId())));
                            sb = sb2.toString();
                            break;
                        case 3:
                            sb2.append(Arrays.hashCode(icon2.getDataBytes()));
                            sb = sb2.toString();
                            break;
                        case 4:
                        case 6:
                            sb2.append(icon2.getUriString());
                            sb = sb2.toString();
                            break;
                        default:
                            sb = sb2.toString();
                            break;
                    }
                    final String str2 = sb;
                    final int i3 = i2;
                    final int i4 = i;
                    Icon.OnDrawableLoadedListener listener = new Icon.OnDrawableLoadedListener() { // from class: com.google.android.systemui.smartspace.uitemplate.SubImageTemplateCard$$ExternalSyntheticLambda0
                        @Override // android.graphics.drawable.Icon.OnDrawableLoadedListener
                        public final void onDrawableLoaded(Drawable drawable) {
                            SubImageTemplateCard subImageTemplateCard = SubImageTemplateCard.this;
                            String str3 = str;
                            String str4 = str2;
                            Map map = treeMap;
                            int i5 = i3;
                            List list = subImages;
                            final int i6 = i4;
                            WeakReference weakReference2 = weakReference;
                            if (!str3.equals(subImageTemplateCard.mPrevSmartspaceTargetId)) {
                                Log.d("SubImageTemplateCard", "SmartspaceTarget has changed. Skip the loaded result...");
                                return;
                            }
                            subImageTemplateCard.mIconDrawableCache.put(str4, drawable);
                            map.put(Integer.valueOf(i5), drawable);
                            if (map.size() == list.size()) {
                                final AnimationDrawable animationDrawable = new AnimationDrawable();
                                List list2 = (List) map.values().stream().filter(new Predicate() { // from class: com.google.android.systemui.smartspace.uitemplate.SubImageTemplateCard$$ExternalSyntheticLambda3
                                    @Override // java.util.function.Predicate
                                    public final boolean test(Object obj) {
                                        return Objects.nonNull((Drawable) obj);
                                    }
                                }).collect(Collectors.toList());
                                if (list2.isEmpty()) {
                                    Log.w("SubImageTemplateCard", "All images are failed to load. Reset imageView");
                                    ImageView imageView = subImageTemplateCard.mImageView;
                                    if (imageView != null) {
                                        imageView.getLayoutParams().width = -2;
                                        subImageTemplateCard.mImageView.setImageDrawable(null);
                                        subImageTemplateCard.mImageView.setBackgroundTintList(null);
                                        return;
                                    }
                                    return;
                                }
                                list2.forEach(obj -> animationDrawable.addFrame((Drawable) obj, i6));
                                ImageView imageView2 = (ImageView) weakReference2.get();
                                imageView2.setImageDrawable(animationDrawable);
                                int intrinsicWidth = animationDrawable.getIntrinsicWidth();
                                if (imageView2.getLayoutParams().width != intrinsicWidth) {
                                    Log.d("SubImageTemplateCard", "imageView requestLayout");
                                    imageView2.getLayoutParams().width = intrinsicWidth;
                                    imageView2.requestLayout();
                                }
                                animationDrawable.start();
                            }
                        }
                    };
                    if (this.mIconDrawableCache.containsKey(sb) && this.mIconDrawableCache.get(sb) != null) {
                        listener.onDrawableLoaded((Drawable) this.mIconDrawableCache.get(sb));
                    } else if (icon2.getType() == 4) {
                        new LoadUriTask().execute(new DrawableWrapper(icon2.getUri(), contentResolver, this.mImageHeight, listener));
                    } else {
                        icon2.loadDrawableAsync(getContext(), listener, this.mHandler);
                    }
                }
            }
            if (subImageAction != null) {
                BcSmartSpaceUtil.setOnClickListener(this, smartspaceTarget, subImageAction, smartspaceEventNotifier, "SubImageTemplateCard", bcSmartspaceCardLoggingInfo);
                return true;
            }
            return true;
        }
        Log.w("SubImageTemplateCard", "SubImageTemplateData is null or has no SubImage");
        return false;
    }

    public final void onFinishInflate() {
        super/*android.view.ViewGroup*/.onFinishInflate();
        this.mImageView = (ImageView) findViewById(R.id.image_view);
    }
}
