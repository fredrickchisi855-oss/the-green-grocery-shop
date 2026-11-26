package zw.fxdevelopment.greengrocery;

import android.app.Activity;
import android.graphics.Color;
import android.view.WindowManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.PermissionRequest;
import android.content.pm.PackageManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfDocument;
import android.content.ContentValues;
import android.provider.MediaStore;
import android.content.Intent;
import java.io.File;
import java.io.OutputStream;
import org.json.JSONObject;

// Cloud-authorized owner recovery bridge; local database remains offline-first.
public class MainActivity extends Activity {
    private WebView webView;
    private ShopDatabase database;
    private CloudSyncManager cloudSync;
    private RemoteUiManager remoteUi;
    private String allowedLocalPrefix = "file:///android_asset/";
    private final Handler syncHandler = new Handler(Looper.getMainLooper());
    private final Runnable periodicSync = new Runnable() { @Override public void run() { if (cloudSync != null) cloudSync.requestSync(); syncHandler.postDelayed(this, 15000L); } };
    private FrameLayout root;
    private PermissionRequest pendingWebPermission;
    private static final int CAMERA_PERMISSION_REQUEST = 4102;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.setStatusBarColor(Color.rgb(14, 90, 31));
        window.setNavigationBarColor(Color.WHITE);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        database = new ShopDatabase(this);
        cloudSync = new CloudSyncManager(this, database);
        remoteUi = new RemoteUiManager(this);
        cloudSync.setListener(() -> runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript("if(window.onCloudSyncUpdated){window.onCloudSyncUpdated();}", null);
        }));
        root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        webView = new WebView(this);
        // Prevent WebView text selection and Android's long-press copy menu.
        webView.setLongClickable(false);
        webView.setHapticFeedbackEnabled(false);
        webView.setOnLongClickListener(v -> true);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowFileAccessFromFileURLs(false);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(false);
        webView.getSettings().setAllowContentAccess(false);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        webView.getSettings().setSupportMultipleWindows(false);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean wantsCamera = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) wantsCamera = true;
                    }
                    if (!wantsCamera) { request.deny(); return; }
                    if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        pendingWebPermission = request;
                        requestPermissions(new String[]{android.Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
                    }
                });
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingWebPermission == request) pendingWebPermission = null;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String target = request.getUrl().toString();
                return !(target.startsWith("file:///android_asset/") || target.startsWith(allowedLocalPrefix));
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (!(url.startsWith("file:///android_asset/") || url.startsWith(allowedLocalPrefix))) view.stopLoading();
                else super.onPageStarted(view, url, favicon);
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame() && !request.getUrl().toString().startsWith("file:///android_asset/")) {
                    android.util.Log.e("GreenGrocery", "Cached dashboard failed to load; opening bundled dashboard: " + error.getDescription());
                    allowedLocalPrefix = "file:///android_asset/";
                    view.loadUrl("file:///android_asset/www/index.html");
                } else if (request.isForMainFrame()) {
                    android.util.Log.e("GreenGrocery", "Bundled dashboard failed to load: " + error.getDescription());
                }
            }
        });
        webView.addJavascriptInterface(new ShopBridge(), "AndroidShopDB");
        root.addView(webView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        File cachedDashboard = remoteUi.getActiveIndexFile();
        if (cachedDashboard != null) {
            allowedLocalPrefix = Uri.fromFile(cachedDashboard.getParentFile()).toString();
            if (!allowedLocalPrefix.endsWith("/")) allowedLocalPrefix += "/";
            webView.loadUrl(Uri.fromFile(cachedDashboard).toString());
        } else {
            webView.loadUrl("file:///android_asset/www/index.html");
        }
        // Verified dashboard updates are staged in the background and used next launch.
        remoteUi.checkForUpdates(status -> android.util.Log.i("GreenGrocery", status));
        syncHandler.postDelayed(periodicSync, 60000L);
    }

    private String createSalesPdf(String title,String reportText,String fileName) {
        if(database==null||!database.isOwnerSession())return "{\"ok\":false,\"message\":\"Owner access required\"}";
        if(reportText==null||reportText.length()>500000)return "{\"ok\":false,\"message\":\"Report too large\"}";
        PdfDocument pdf=new PdfDocument();Uri uri=null;Bitmap shopLogo=null;
        try{
            final int width=595,height=842,left=42,right=width-42,contentWidth=right-left;
            try(java.io.InputStream in=getAssets().open("www/brand_logo.jpg")){shopLogo=BitmapFactory.decodeStream(in);}catch(Exception ignored){}
            Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);text.setColor(Color.rgb(35,48,40));text.setTextSize(9.2f);
            Paint muted=new Paint(Paint.ANTI_ALIAS_FLAG);muted.setColor(Color.rgb(105,117,109));muted.setTextSize(8.2f);
            Paint green=new Paint(Paint.ANTI_ALIAS_FLAG);green.setColor(Color.rgb(14,90,31));
            Paint pale=new Paint(Paint.ANTI_ALIAS_FLAG);pale.setColor(Color.rgb(235,245,237));
            Paint grid=new Paint(Paint.ANTI_ALIAS_FLAG);grid.setColor(Color.rgb(205,218,208));grid.setStrokeWidth(.65f);
            Paint white=new Paint(Paint.ANTI_ALIAS_FLAG);white.setColor(Color.WHITE);
            PdfDocument.Page page=null;android.graphics.Canvas canvas=null;int y=0,pageNo=0;
            java.util.function.Consumer<Void> unused=null;
            String[] lines=reportText.split("\\n",-1);
            for(int li=0;li<lines.length;li++){
                String line=lines[li]==null?"":lines[li].trim();
                if(line.isEmpty()){y+=7;continue;}
                boolean section=line.equals("SUMMARY")||line.equals("PAYMENT METHOD TOTALS")||line.equals("CASHIER TOTALS")||line.equals("PRODUCT SALES")||line.equals("TRANSACTION DETAILS");
                if(page==null||y>height-78){
                    if(page!=null){
                        canvas.drawLine(left,height-48,right,height-48,grid);
                        muted.setTextSize(8);canvas.drawText("THE GREEN GROCERY SHOP  |  FX DEVELOPMENT",left,height-30,muted);
                        String p="Page "+pageNo;canvas.drawText(p,right-muted.measureText(p),height-30,muted);pdf.finishPage(page);
                    }
                    pageNo++;page=pdf.startPage(new PdfDocument.PageInfo.Builder(width,height,pageNo).create());canvas=page.getCanvas();
                    canvas.drawColor(Color.WHITE);
                    if(shopLogo!=null){android.graphics.Rect dst=new android.graphics.Rect(left,28,left+52,80);canvas.drawBitmap(shopLogo,null,dst,new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));}
                    Paint brand=new Paint(Paint.ANTI_ALIAS_FLAG);brand.setColor(Color.rgb(14,90,31));brand.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD));brand.setTextSize(16);
                    canvas.drawText("THE GREEN GROCERY SHOP",left+64,47,brand);
                    brand.setTextSize(11);brand.setTypeface(android.graphics.Typeface.DEFAULT);canvas.drawText(title==null?"Business Sales Report":title,left+64,66,brand);
                    muted.setTextSize(8.5f);canvas.drawText("FX DEVELOPMENT  |  SHOP MANAGEMENT",left+64,82,muted);
                    canvas.drawLine(left,101,right,101,green);
                    y=126;
                }
                if(section){
                    y+=5;
                    Paint heading=new Paint(Paint.ANTI_ALIAS_FLAG);heading.setColor(Color.rgb(14,90,31));heading.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD));heading.setTextSize(10);
                    canvas.drawText(line,left,y+5,heading);canvas.drawLine(left,y+11,right,y+11,green);y+=22;continue;
                }
                if(line.startsWith("Period:")||line.startsWith("Generated:")){
                    muted.setTextSize(8.5f);canvas.drawText(line,left,y,muted);y+=14;continue;
                }
                if(line.startsWith("Note:")||line.startsWith("Item-level details")){
                    text.setTextSize(8.2f);int n=text.breakText(line,true,contentWidth,null);canvas.drawText(line.substring(0,n),left,y,text);y+=14;continue;
                }
                String[] cells=line.split("\\s*\\|\\s*",-1);
                if(cells.length>=2){
                    int count=Math.min(cells.length,5);
                    float[] fractions;
                    if(count==2)fractions=new float[]{.70f,.30f};
                    else if(count==3)fractions=new float[]{.48f,.22f,.30f};
                    else if(count==4)fractions=new float[]{.40f,.13f,.23f,.24f};
                    else fractions=new float[]{.22f,.17f,.22f,.20f,.19f};
                    boolean header=cells[0].equalsIgnoreCase("Metric")||cells[0].equalsIgnoreCase("Payment method")||cells[0].equalsIgnoreCase("Cashier")||cells[0].equalsIgnoreCase("Product");
                    int rowH=24;
                    Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);fill.setColor(header?Color.rgb(229,241,232):Color.WHITE);
                    canvas.drawRect(left,y-13,right,y+rowH-13,fill);
                    canvas.drawRect(left,y-13,right,y+rowH-13,grid);
                    float x=left;
                    for(int c=0;c<count;c++){
                        if(c>0)canvas.drawLine(x,y-13,x,y+rowH-13,grid);
                        Paint cell=new Paint(Paint.ANTI_ALIAS_FLAG);cell.setColor(header?Color.rgb(14,90,31):Color.rgb(35,48,40));cell.setTextSize(header?8.2f:8.1f);
                        cell.setTypeface(header?android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD):android.graphics.Typeface.DEFAULT);
                        float pad=6;int n=cell.breakText(cells[c],true,Math.max(8,contentWidth*fractions[c]-pad*2),null);
                        String value=cells[c].substring(0,n);
                        if(c==0||count==2)canvas.drawText(value,x+pad,y+3,cell);
                        else canvas.drawText(value,x+pad,y+3,cell);
                        x+=contentWidth*fractions[c];
                    }
                    y+=rowH;continue;
                }
                if(line.contains(":")){
                    int colon=line.indexOf(':');String label=line.substring(0,colon+1),value=line.substring(colon+1).trim();
                    text.setTextSize(9);text.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD));canvas.drawText(label,left+4,y,text);
                    text.setTypeface(android.graphics.Typeface.DEFAULT);int n=text.breakText(value,true,contentWidth-150,null);canvas.drawText(value.substring(0,n),left+150,y,text);y+=17;continue;
                }
                text.setTextSize(9);int n=text.breakText(line,true,contentWidth,null);canvas.drawText(line.substring(0,n),left+4,y,text);y+=15;
            }
            if(page!=null){
                canvas.drawLine(left,height-48,right,height-48,grid);
                muted.setTextSize(8);canvas.drawText("THE GREEN GROCERY SHOP  |  FX DEVELOPMENT",left,height-30,muted);
                String p="Page "+pageNo;canvas.drawText(p,right-muted.measureText(p),height-30,muted);pdf.finishPage(page);
            }
            String name=(fileName==null?"Green-Grocery-Report.pdf":fileName).replaceAll("[^A-Za-z0-9._-]","_");if(!name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf"))name+=".pdf";
            if(Build.VERSION.SDK_INT>=29){
                ContentValues values=new ContentValues();values.put(MediaStore.Downloads.DISPLAY_NAME,name);values.put(MediaStore.Downloads.MIME_TYPE,"application/pdf");values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/Green Grocery Reports");values.put(MediaStore.Downloads.IS_PENDING,1);
                uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IllegalStateException("Cannot create Downloads file");
                try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IllegalStateException("Cannot write PDF");pdf.writeTo(out);}
                ContentValues done=new ContentValues();done.put(MediaStore.Downloads.IS_PENDING,0);getContentResolver().update(uri,done,null,null);
            }else{
                File dir=getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);if(dir==null)throw new IllegalStateException("Downloads unavailable");if(!dir.exists())dir.mkdirs();try(OutputStream out=new java.io.FileOutputStream(new File(dir,name))){pdf.writeTo(out);}
            }
            if(uri!=null){final Uri shareUri=uri;runOnUiThread(()->{Intent send=new Intent(Intent.ACTION_SEND);send.setType("application/pdf");send.putExtra(Intent.EXTRA_STREAM,shareUri);send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(send,"Share sales report PDF"));});}
            org.json.JSONObject out=new org.json.JSONObject();out.put("ok",true);out.put("file",name);return out.toString();
        }catch(Exception e){try{if(uri!=null)getContentResolver().delete(uri,null,null);}catch(Exception ignored){}org.json.JSONObject out=new org.json.JSONObject();try{out.put("ok",false);out.put("message","PDF export failed: "+(e.getMessage()==null?"unknown":e.getMessage()));}catch(Exception ignored){}return out.toString();}
        finally{if(shopLogo!=null&&!shopLogo.isRecycled())shopLogo.recycle();pdf.close();}
    }

    private final class ShopBridge {
        @JavascriptInterface public boolean hasAccounts() { return database.hasAccounts(); }
        @JavascriptInterface public String createOwner(String username,String displayName,String password) {
            if (cloudSync == null) return "{\"ok\":false,\"message\":\"Cloudflare registration verification is unavailable. Connect to the internet and try again.\"}";
            try {
                org.json.JSONObject status = new org.json.JSONObject(cloudSync.ownerRegistrationStatus());
                if (!status.optBoolean("ok", false)) return new org.json.JSONObject().put("ok", false)
                    .put("message", "The shop registration status could not be verified. Administrator setup is locked until the shop can be checked online.").toString();
                if (status.optBoolean("registered", true)) return new org.json.JSONObject().put("ok", false)
                    .put("message", "This shop already has its permanent Administrator. A second Administrator cannot be created.").toString();
                org.json.JSONObject enrolled = new org.json.JSONObject(cloudSync.enrollOwner(username, displayName, password));
                if (!enrolled.optBoolean("ok", false)) return enrolled.toString();
                boolean created = database.createOwnerOffline(username, displayName, password);
                if (!created) return new org.json.JSONObject().put("ok", false)
                    .put("message", "Cloudflare has secured the first Administrator registration, but the local account could not be saved. Keep this authorised device intact and contact support for recovery.").toString();
                cloudSync.requestSync();
                return new org.json.JSONObject().put("ok", true)
                    .put("message", "First Administrator registered with Cloudflare and saved on this phone. Shop-wide registration is now locked.").toString();
            } catch (Exception error) {
                return "{\"ok\":false,\"message\":\"Administrator registration could not be verified. Please try again while online.\"}";
            }
        }
        @JavascriptInterface public String getShopRegistrationStatus() {
            return cloudSync == null ? "{\"ok\":false}" : cloudSync.ownerRegistrationStatus();
        }
        @JavascriptInterface public String changeOwnerPassword(String newPassword) {
            if (!database.isOwnerSession()) return "{\"ok\":false,\"message\":\"Administrator sign-in is required.\"}";
            String verifier = database.ownerPasswordVerifier(newPassword);
            if (verifier == null) return "{\"ok\":false,\"message\":\"Use a password between 10 and 128 characters.\"}";
            String username = database.activeOwnerUsername();
            if (username == null || cloudSync == null) return "{\"ok\":false,\"message\":\"Administrator account service is unavailable.\"}";
            try {
                org.json.JSONObject result = new org.json.JSONObject(cloudSync.changeOwnerPassword(username, verifier));
                if (!result.optBoolean("ok", false)) return result.toString();
                if (!database.applyOwnerPasswordVerifier(verifier))
                    return "{\"ok\":false,\"message\":\"Cloudflare updated the password, but this phone could not update its local record. Keep this app open and contact support.\"}";
                return new org.json.JSONObject().put("ok", true).put("message", "Password changed successfully on this shop and synchronised with Cloudflare.").toString();
            } catch (Exception error) { return "{\"ok\":false,\"message\":\"Password update could not be completed.\"}"; }
        }
        @JavascriptInterface public String getAccountDiagnostics() { return database.accountDiagnostics(); }
        @JavascriptInterface public boolean createCashier(String u, String n, String pin, String owner) { boolean ok=database.createCashier(u,n,pin,owner); if(ok && cloudSync!=null) cloudSync.requestSync(); return ok; }
        @JavascriptInterface public String listCashiers(String owner) { return database.listCashiers(owner); }
        @JavascriptInterface public boolean removeCashier(String id, String owner) { boolean ok=database.removeCashier(id,owner); if(ok && cloudSync!=null) cloudSync.requestSync(); return ok; }
        @JavascriptInterface public String authenticate(String u, String secret) {
            String local = database.authenticate(u, secret);
            if (local != null) return local;
            if (cloudSync == null) return null;
            String online = cloudSync.authenticateOnline(u, secret);
            if (online == null) return null;
            return database.authenticate(u, secret);
        }
        @JavascriptInterface public void logout() { database.logout(); }
        @JavascriptInterface public String loadState() { return database.loadAppState(); }
        @JavascriptInterface public boolean saveState(String json) { boolean saved=database.saveAppState(json); if(saved && cloudSync!=null) cloudSync.requestSync(); return saved; }
        @JavascriptInterface public String pairDevice(String code, String label) { return cloudSync==null ? "{\"ok\":false}" : cloudSync.pairDevice(code,label); }
        @JavascriptInterface public void syncNow() { if(cloudSync!=null) cloudSync.requestSync(); }
        @JavascriptInterface public String getSyncStatus() { return cloudSync==null ? "{\"paired\":false}" : cloudSync.statusJson(); }
        @JavascriptInterface public String generateSalesPdf(String title,String reportText,String filename){return createSalesPdf(title,reportText,filename);}
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST && pendingWebPermission != null) {
            PermissionRequest request = pendingWebPermission;
            pendingWebPermission = null;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                request.deny();
            }
        }
    }

    @Override protected void onResume() { super.onResume(); if (cloudSync != null) cloudSync.requestSync(); }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        syncHandler.removeCallbacks(periodicSync);
        if (database != null) database.logout();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
