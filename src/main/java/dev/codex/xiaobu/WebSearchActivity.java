package dev.codex.xiaobu;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground user initiated upload; uses the module's network identity and browser defaults. */
public final class WebSearchActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "XiaobuWebUpload"));
    private volatile boolean destroyed;
    private int engine;
    private boolean web;
    private byte[] imageBytes;
    private Uri image;
    private TextView message;
    private ProgressBar progress;
    private Button retry;
    private String result;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent request = getIntent();
        engine = request.getIntExtra(ScreenshotActions.EXTRA_ENGINE, -1);
        // Older hosts only started this activity for public web searches.
        web = request.hasExtra(ScreenshotActions.EXTRA_WEB)
                ? request.getBooleanExtra(ScreenshotActions.EXTRA_WEB, true) : true;
        imageBytes = request.getByteArrayExtra(ScreenshotActions.EXTRA_IMAGE);
        image = request.getParcelableExtra(Intent.EXTRA_STREAM);
        boolean nativeApp = !web && (engine == 0 || engine == 1);
        boolean webSearch = WebSearchRoutes.isWeb(engine) || engine == 2;
        if (!nativeApp && (!webSearch || !hasImage())) { finish(); return; }
        if (webSearch && engine != 2) DirectNetwork.install(getApplicationContext());
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad, pad, pad);
        message = new TextView(this);
        message.setTextSize(17);
        message.setGravity(Gravity.CENTER);
        panel.addView(message);
        progress = new ProgressBar(this);
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(pad * 2, pad * 2);
        spacing.topMargin = pad;
        panel.addView(progress, spacing);
        retry = new Button(this);
        retry.setText("重试");
        retry.setOnClickListener(v -> { if (result == null) upload(); else openResult(); });
        panel.addView(retry);
        setContentView(panel);
        getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - pad * 2, pad * 13), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        setFinishOnTouchOutside(false);
        upload();
    }

    private boolean hasImage() {
        return imageBytes != null && imageBytes.length > 0
                || image != null && "content".equals(image.getScheme());
    }

    private void upload() {
        android.util.Log.i("XiaobuWebSearch", "upload.begin engine=" + engine + " web=" + web + " bytes=" + (imageBytes == null ? -1 : imageBytes.length));
        boolean nativeApp = !web && (engine == 0 || engine == 1);
        message.setText(nativeApp ? "正在打开识图应用…"
                : engine == 2 ? "正在打开抖音拍照问…"
                : "正在使用 " + WebSearchRoutes.name(engine) + " 识图…");
        progress.setVisibility(android.view.View.VISIBLE);
        retry.setVisibility(android.view.View.GONE);
        worker.execute(() -> {
            try {
                if (nativeApp) {
                    openNative();
                    runOnUiThread(() -> { if (!destroyed) finish(); });
                    return;
                }
                if (engine == 2) {
                    openDouyin();
                    runOnUiThread(() -> { if (!destroyed) finish(); });
                    return;
                }
                byte[] compressed = imageBytes != null && imageBytes.length > 0 ? imageBytes : searchImage();
                android.util.Log.i("XiaobuWebSearch", "image.ready engine=" + engine + " bytes=" + compressed.length);
                String link = WebImageSearch.upload(new ByteArrayInputStream(compressed), engine, host ->
                        runOnUiThread(() -> { if (!destroyed) message.setText("正在上传到 " + host + "…"); }));
                android.util.Log.i("XiaobuWebSearch", "result.ready engine=" + engine + " active=" + !destroyed);
                runOnUiThread(() -> { if (!destroyed) { result = link; openResult(); } });
            } catch (Exception error) {
                android.util.Log.i("XiaobuWebSearch", "upload.error engine=" + engine + " type=" + error.getClass().getSimpleName());
                String detail = nativeApp ? "无法打开识图应用"
                        : engine == 2 ? "无法打开抖音拍照问，请确认模块已获得 root 权限且抖音版本受支持（支持 40.5.0 / 40.8.0）"
                        : error instanceof java.net.SocketTimeoutException ? "连接超时，请检查网络后重试"
                        : error instanceof javax.net.ssl.SSLException ? "安全连接失败，请检查网络"
                        : error instanceof java.net.UnknownHostException ? "无法连接识图服务，请检查网络"
                        : "上传失败，请重试（" + error.getClass().getSimpleName() + "）";
                runOnUiThread(() -> { if (!destroyed) { message.setText(detail); progress.setVisibility(android.view.View.GONE); retry.setVisibility(android.view.View.VISIBLE); } });
            }
        });
    }

    /** The module owns the provider, so the engine only needs the forwarded read grant. */
    private void openNative() throws Exception {
        Uri uri = getContentResolver().insert(SearchImageProvider.CONTENT, new ContentValues());
        if (uri == null) throw new java.io.IOException("Search cache insert returned null");
        boolean written = false;
        try {
            try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
                if (stream == null) throw new java.io.IOException("Search cache unavailable");
                if (imageBytes != null && imageBytes.length > 0) {
                    stream.write(imageBytes);
                } else if (image != null) {
                    try (InputStream input = getContentResolver().openInputStream(image)) {
                        if (input == null) throw new java.io.IOException("Screenshot unavailable");
                        byte[] buffer = new byte[16384];
                        int read;
                        while ((read = input.read(buffer)) != -1) stream.write(buffer, 0, read);
                    }
                } else {
                    throw new java.io.IOException("Screenshot unavailable");
                }
            }
            written = true;
            Intent share = ScreenshotActions.resolveShare(getApplicationContext(), engine, uri, null);
            if (share == null) throw new java.io.IOException("Image search app unavailable");
            ScreenshotActions.launch(getApplicationContext(), engine, uri, share);
            android.util.Log.i("XiaobuWebSearch", "engine.started engine=" + engine + " uri=" + uri);
        } finally {
            if (!written) {
                try { getContentResolver().delete(uri, null, null); }
                catch (Exception ignored) { }
            }
        }
    }

    private void openDouyin() throws Exception {
        Bitmap picture = null;
        boolean owned = false;
        try {
            if (imageBytes != null && imageBytes.length > 0) {
                picture = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                owned = true;
            } else {
                picture = decode(image);
            }
            if (picture == null) throw new java.io.IOException("Screenshot unavailable");
            DouyinImageSearch.open(getApplicationContext(), picture);
        } finally {
            if (owned && picture != null && !picture.isRecycled()) picture.recycle();
        }
    }

    private Bitmap decode(Uri source) throws Exception {
        if (source == null) return null;
        try (InputStream input = getContentResolver().openInputStream(source)) {
            return input == null ? null : BitmapFactory.decodeStream(input);
        }
    }

    /** Legacy host path: resize and compress a screenshot that arrives as a URI. */
    private byte[] searchImage() throws Exception {
        Bitmap source = decode(image);
        if (source == null) throw new java.io.IOException("Screenshot unavailable");
        try {
            if (source.getWidth() < 1 || source.getHeight() < 1
                    || source.getWidth() > 20000 || source.getHeight() > 20000)
                throw new java.io.IOException("Invalid image dimensions");
            int sample = 1;
            while (Math.max(source.getWidth(), source.getHeight()) / sample > 1600) sample *= 2;
            Bitmap picture = sample == 1 ? source : Bitmap.createScaledBitmap(source,
                    Math.max(1, source.getWidth() / sample), Math.max(1, source.getHeight() / sample), true);
            try {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                if (!picture.compress(Bitmap.CompressFormat.JPEG, 92, output)) throw new java.io.IOException("Image encoding failed");
                return output.toByteArray();
            } finally {
                if (picture != source && !picture.isRecycled()) picture.recycle();
            }
        } finally {
            if (!source.isRecycled()) source.recycle();
        }
    }

    private void openResult() {
        try {
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(result));
            browser.addCategory(Intent.CATEGORY_BROWSABLE);
            android.content.pm.ResolveInfo chosen = getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/")).addCategory(Intent.CATEGORY_BROWSABLE),
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if (chosen != null && chosen.activityInfo != null && !"android".equals(chosen.activityInfo.packageName)) browser.setPackage(chosen.activityInfo.packageName);
            android.util.Log.i("XiaobuWebSearch", "browser.request engine=" + engine + " package=" + browser.getPackage());
            startActivity(browser);
            android.util.Log.i("XiaobuWebSearch", "browser.started engine=" + engine);
            finish();
        } catch (Exception error) {
            message.setText("无法打开默认浏览器");
            progress.setVisibility(android.view.View.GONE);
            retry.setVisibility(android.view.View.VISIBLE);
        }
    }

    @Override protected void onDestroy() {
        android.util.Log.i("XiaobuWebSearch", "activity.destroy engine=" + engine);
        destroyed = true;
        worker.shutdownNow();
        super.onDestroy();
    }
}
