package com.android.keyguard.clocks;

import android.app.WallpaperManager;
import android.graphics.Color;
import android.app.WallpaperColors;
import android.content.Context;
import android.support.v7.graphics.Palette;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Paint;
import android.os.ParcelFileDescriptor;
import android.graphics.BitmapFactory;

import java.lang.NullPointerException;
import java.lang.IllegalStateException;

public class ColorText {

    public static int getWallColor(Context mContext) {
            Bitmap mBitmap;
            //Get wallpaper as bitmap
            WallpaperManager manager = WallpaperManager.getInstance(mContext);
            ParcelFileDescriptor pfd = manager.getWallpaperFile(WallpaperManager.FLAG_LOCK);

            //Sometimes lock wallpaper maybe null as getWallpaperFile doesnt return builtin wallpaper
            if (pfd == null)
                pfd = manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM);

            try {
                if (pfd != null) {
                    mBitmap = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
                } else {
                    //Incase both cases return null wallpaper, generate a yellow bitmap
                    mBitmap = drawEmpty();
                }
                Palette palette = Palette.generate(mBitmap);

                //For monochrome and single color bitmaps, the value returned is 0
                if (Color.valueOf(palette.getLightVibrantColor(0x000000)).toArgb() == 0) {
                    //So get bodycolor on dominant color instead as a hacky workaround
                    return Color.valueOf(palette.getDominantSwatch().getBodyTextColor()).toArgb();

                //On Black Wallpapers set color to White
                } else if(String.format("#%06X", (0xFFFFFF & (palette.getLightVibrantColor(0x000000)))) == "#000000") {
                    return Color.WHITE;
                } else {
                    return Color.valueOf(palette.getLightVibrantColor(0xff000000)).toArgb();
                }

              //Just a fallback, although I doubt this case will ever come
            } catch (NullPointerException e) {
                return Color.WHITE;
            }
    }

    private static Bitmap drawEmpty() {
        Bitmap convertedBitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(convertedBitmap);
        Paint paint = new Paint();
        paint.setColor(Color.YELLOW);
        canvas.drawPaint(paint);
        return convertedBitmap;
    }
}
