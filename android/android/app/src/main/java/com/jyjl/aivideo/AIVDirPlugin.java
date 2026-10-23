package com.jyjl.aivideo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 安卓目录选择 + 远程视频写入所选目录；系统相册/文件选图（必要时申请照片权限）。
 */
@CapacitorPlugin(name = "AIVDir")
public class AIVDirPlugin extends Plugin {

    private static final String PREF = "aiv_dir_store";
    private static final String KEY_URI = "tree_uri";
    private static final String KEY_NAME = "tree_name";
    private PluginCall pendingImageCall;

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private Uri treeUri() {
        String s = prefs().getString(KEY_URI, null);
        return s == null ? null : Uri.parse(s);
    }

    // ===== 目录 =====

    @PluginMethod
    public void pickDirectory(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        );
        try {
            startActivityForResult(call, intent, "pickDirectoryResult");
        } catch (Exception e) {
            JSObject ret = new JSObject();
            ret.put("ok", false);
            ret.put("error", String.valueOf(e.getMessage()));
            call.resolve(ret);
        }
    }

    @ActivityCallback
    private void pickDirectoryResult(PluginCall call, ActivityResult result) {
        JSObject ret = new JSObject();
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            ret.put("ok", false);
            ret.put("cancelled", true);
            call.resolve(ret);
            return;
        }
        Intent data = result.getData();
        Uri uri = data.getData();
        if (uri == null) {
            ret.put("ok", false);
            ret.put("error", "未拿到目录");
            call.resolve(ret);
            return;
        }
        try {
            final int takeFlags =
                data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContext().getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (Exception ignored) {
        }
        String name = queryName(uri);
        prefs().edit().putString(KEY_URI, uri.toString()).putString(KEY_NAME, name).apply();
        ret.put("ok", true);
        ret.put("path", name);
        ret.put("uri", uri.toString());
        call.resolve(ret);
    }

    // ===== 系统相册 / 文件选图 =====

    private String[] imagePerms() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[] { Manifest.permission.READ_MEDIA_IMAGES };
        }
        return new String[] { Manifest.permission.READ_EXTERNAL_STORAGE };
    }

    private boolean hasImagePerm() {
        for (String p : imagePerms()) {
            if (getContext().checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @PluginMethod
    public void pickImage(PluginCall call) {
        // Photo Picker / SAF 一般不强制权限；仍先申请，便于浏览本机相册
        if (!hasImagePerm()) {
            pendingImageCall = call;
            try {
                requestAllPermissions(call, "imagePermissionResult");
            } catch (Exception e) {
                openImagePicker(call);
            }
            return;
        }
        openImagePicker(call);
    }

    @PermissionCallback
    private void imagePermissionResult(PluginCall call) {
        PluginCall c = pendingImageCall != null ? pendingImageCall : call;
        pendingImageCall = null;
        // 即使拒绝也尝试打开系统选择器（Photo Picker 可不需权限）
        openImagePicker(c);
    }

    private void openImagePicker(PluginCall call) {
        Intent intent;
        try {
            intent = new Intent("android.provider.action.PICK_IMAGES");
            intent.setType("image/*");
        } catch (Exception e) {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        try {
            startActivityForResult(call, intent, "pickImageResult");
        } catch (Exception e) {
            try {
                Intent alt = new Intent(Intent.ACTION_GET_CONTENT);
                alt.setType("image/*");
                alt.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(call, alt, "pickImageResult");
            } catch (Exception e2) {
                JSObject ret = new JSObject();
                ret.put("ok", false);
                ret.put("error", String.valueOf(e2.getMessage()));
                call.resolve(ret);
            }
        }
    }

    @ActivityCallback
    private void pickImageResult(PluginCall call, ActivityResult result) {
        JSObject ret = new JSObject();
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            ret.put("ok", false);
            ret.put("cancelled", true);
            call.resolve(ret);
            return;
        }
        Uri uri = result.getData().getData();
        if (uri == null) {
            ret.put("ok", false);
            ret.put("error", "未拿到图片");
            call.resolve(ret);
            return;
        }
        try {
            InputStream in = getContext().getContentResolver().openInputStream(uri);
            if (in == null) {
                ret.put("ok", false);
                ret.put("error", "无法读取图片");
                call.resolve(ret);
                return;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            byte[] bytes = bos.toByteArray();
            String mime = getContext().getContentResolver().getType(uri);
            if (mime == null || mime.isEmpty()) mime = "image/jpeg";
            String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            String name = queryDisplay(uri);
            ret.put("ok", true);
            ret.put("name", name);
            ret.put("mime", mime);
            ret.put("bytes", bytes.length);
            ret.put("dataUrl", "data:" + mime + ";base64," + b64);
            call.resolve(ret);
        } catch (Exception e) {
            ret.put("ok", false);
            ret.put("error", String.valueOf(e.getMessage()));
            call.resolve(ret);
        }
    }

    // ===== 状态 / 重置 / 下载 =====

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject ret = new JSObject();
        Uri u = treeUri();
        ret.put("ok", true);
        ret.put("hasDir", u != null);
        ret.put("path", u != null ? prefs().getString(KEY_NAME, "所选目录") : "应用目录（默认）");
        call.resolve(ret);
    }

    @PluginMethod
    public void resetDirectory(PluginCall call) {
        prefs().edit().remove(KEY_URI).remove(KEY_NAME).apply();
        JSObject ret = new JSObject();
        ret.put("ok", true);
        ret.put("path", "应用目录（默认）");
        call.resolve(ret);
    }

    @PluginMethod
    public void saveUrl(PluginCall call) {
        String url = call.getString("url");
        String filename = call.getString("filename", "video.mp4");
        if (url == null || url.isEmpty()) {
            JSObject ret = new JSObject();
            ret.put("ok", false);
            ret.put("error", "缺少 url");
            call.resolve(ret);
            return;
        }
        final PluginCall c = call;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL u = new URL(url);
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(120000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "AI-Video-Workbench/2.4");
                    int code = conn.getResponseCode();
                    if (code >= 400) {
                        JSObject ret = new JSObject();
                        ret.put("ok", false);
                        ret.put("error", "HTTP " + code);
                        c.resolve(ret);
                        return;
                    }
                    String mime = conn.getContentType();
                    InputStream in = conn.getInputStream();
                    SaveTarget target = openTarget(filename, mime);
                    OutputStream os = target.os;
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        total += n;
                    }
                    os.flush();
                    os.close();
                    in.close();
                    conn.disconnect();
                    JSObject ret = new JSObject();
                    ret.put("ok", true);
                    ret.put("path", target.displayPath);
                    ret.put("bytes", total);
                    c.resolve(ret);
                } catch (Exception e) {
                    JSObject ret = new JSObject();
                    ret.put("ok", false);
                    ret.put("error", String.valueOf(e.getMessage()));
                    c.resolve(ret);
                }
            }
        }).start();
    }

    private static class SaveTarget {
        OutputStream os;
        String displayPath;
    }

    private SaveTarget openTarget(String filename, String mime) throws Exception {
        filename = (filename == null || filename.trim().isEmpty()) ? "video.mp4" : filename.trim().replace('/', '_').replace('\\', '_');
        Uri tree = treeUri();
        if (tree != null) {
            String docId = DocumentsContract.getTreeDocumentId(tree);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
            String m = mime != null ? mime : guessMime(filename);
            Uri created = DocumentsContract.createDocument(getContext().getContentResolver(), parent, m, filename);
            if (created != null) {
                SaveTarget t = new SaveTarget();
                t.os = getContext().getContentResolver().openOutputStream(created);
                t.displayPath = prefs().getString(KEY_NAME, "所选目录") + "/" + filename;
                return t;
            }
        }
        File dir = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = new File(getContext().getFilesDir(), "downloads");
        if (!dir.exists()) dir.mkdirs();
        File dest = new File(dir, filename);
        SaveTarget t = new SaveTarget();
        t.os = new FileOutputStream(dest);
        t.displayPath = dest.getAbsolutePath();
        return t;
    }

    private String guessMime(String name) {
        String ext = "";
        int i = name.lastIndexOf('.');
        if (i >= 0) ext = name.substring(i + 1).toLowerCase();
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "application/octet-stream";
    }

    private String queryDisplay(Uri uri) {
        String name = null;
        try {
            Cursor c = getContext().getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = c.getString(idx);
                }
                c.close();
            }
        } catch (Exception ignored) {
        }
        if (name != null && !name.isEmpty()) return name;
        return queryName(uri);
    }

    private String queryName(Uri uri) {
        try {
            String last = uri.getLastPathSegment();
            if (last != null) {
                int idx = last.lastIndexOf(':');
                String part = idx >= 0 ? last.substring(idx + 1) : last;
                if (part != null && !part.isEmpty() && !"primary".equalsIgnoreCase(part)) {
                    if (part.contains("/")) {
                        String[] seg = part.split("/");
                        return seg[seg.length - 1];
                    }
                    return part;
                }
                if ("primary".equalsIgnoreCase(part)) return "内部存储";
            }
        } catch (Exception ignored) {
        }
        return "所选文件";
    }
}
