package zw.fxdevelopment.greengrocery;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

final class ShopDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME="green_grocery_shop.db";
    private static final int DB_VERSION=4, PBKDF2_ITERATIONS=120000, HASH_BITS=256;
    private final Context context;
    private String activeUserId, activeRole;
    private final Map<String,Integer> failedAttempts=new HashMap<>();
    private final Map<String,Long> lockedUntil=new HashMap<>();

    ShopDatabase(Context context){super(context,DB_NAME,null,DB_VERSION);this.context=context.getApplicationContext();getWritableDatabase();}
    @Override public void onCreate(SQLiteDatabase db){
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(context.getAssets().open("schema.sql"),"UTF-8"))){
            StringBuilder statement=new StringBuilder();String line;
            while((line=reader.readLine())!=null){String trimmed=line.trim();if(trimmed.startsWith("--")||trimmed.isEmpty())continue;
                statement.append(line).append('\n');if(trimmed.endsWith(";")){String sql=statement.toString().trim();if(!sql.isEmpty())db.execSQL(sql);statement.setLength(0);}}
        }catch(Exception error){throw new IllegalStateException("Could not create the shop database",error);}
        db.execSQL("CREATE TABLE IF NOT EXISTS app_state (state_key TEXT PRIMARY KEY, state_json TEXT NOT NULL, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        ensureSyncSchema(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){ ensureSyncSchema(db); }

    private void ensureSyncSchema(SQLiteDatabase db){
        boolean hasBase=false,hasBlocked=false;
        try(Cursor c=db.rawQuery("PRAGMA table_info(sync_queue)",null)){
            while(c.moveToNext()){String n=c.getString(c.getColumnIndexOrThrow("name"));if("base_version".equals(n))hasBase=true;if("blocked".equals(n))hasBlocked=true;}
        }
        if(!hasBase)db.execSQL("ALTER TABLE sync_queue ADD COLUMN base_version INTEGER NOT NULL DEFAULT 0");
        if(!hasBlocked)db.execSQL("ALTER TABLE sync_queue ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_versions (entity_type TEXT NOT NULL, entity_id TEXT NOT NULL, version INTEGER NOT NULL, PRIMARY KEY(entity_type,entity_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_queue_entity_pending ON sync_queue(entity_type,entity_id,synced_at)");
        db.execSQL("CREATE TABLE IF NOT EXISTS owner_enrollment_queue (id TEXT PRIMARY KEY, username TEXT NOT NULL, display_name TEXT NOT NULL, password_salt TEXT NOT NULL, password_hash TEXT NOT NULL, password_iterations INTEGER NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
    }

    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.US,"%02x",b&255));return out.toString();}
    private static byte[] unhex(String value){
        if(value==null||value.length()%2!=0)return new byte[0];byte[] out=new byte[value.length()/2];
        try{for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return out;}catch(Exception e){return new byte[0];}
    }
    private static String salt(){byte[] b=new byte[16];new SecureRandom().nextBytes(b);return hex(b);}
    private static String derive(String secret,String salt,int iterations){
        PBEKeySpec spec=new PBEKeySpec(secret.toCharArray(),unhex(salt),iterations,HASH_BITS);
        try{return hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded());}
        catch(Exception e){throw new IllegalStateException("Password hashing unavailable",e);}finally{spec.clearPassword();}
    }
    private static String encodeSecret(String secret){String s=salt();return "pbkdf2$"+PBKDF2_ITERATIONS+"$"+s+"$"+derive(secret,s,PBKDF2_ITERATIONS);}
    private static boolean verifySecret(String secret,String stored){
        if(stored==null)return false;
        try{
            if(stored.startsWith("pbkdf2$")){String[] p=stored.split("\\$",4);if(p.length!=4)return false;int rounds=Integer.parseInt(p[1]);if(rounds<10000||rounds>1000000)return false;
                byte[] expected=unhex(p[3]),actual=unhex(derive(secret,p[2],rounds));return expected.length==actual.length&&MessageDigest.isEqual(expected,actual);}
            String[] p=stored.split(":",2);if(p.length!=2)return false;
            byte[] expected=p[1].getBytes("UTF-8"),actual=hex(MessageDigest.getInstance("SHA-256").digest((p[0]+":"+secret).getBytes("UTF-8"))).getBytes("UTF-8");
            return MessageDigest.isEqual(expected,actual);
        }catch(Exception e){return false;}
    }
    synchronized boolean hasAccounts(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM app_users",null)){return c.moveToFirst()&&c.getInt(0)>0;}}

    synchronized boolean createOwnerAfterCloudAuthorization(String username,String name,String password) {
        if (activeUserId != null || hasAccounts() || username == null || username.trim().length() < 3 ||
            name == null || name.trim().length() < 2 || password == null || password.length() < 10 || password.length() > 128) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (hasAccounts()) return false;
            ContentValues values = new ContentValues();
            values.put("id", java.util.UUID.randomUUID().toString());
            values.put("username", username.trim());
            values.put("display_name", name.trim());
            values.put("role", "OWNER");
            values.putNull("pin_hash");
            values.put("password_hash", encodeSecret(password));
            values.put("is_active", 1);
            db.insertOrThrow("app_users", null, values);
            db.setTransactionSuccessful();
            return true;
        } catch (Exception error) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    synchronized boolean createOwnerOffline(String username,String name,String password) {
        if (activeUserId != null || hasAccounts() || username == null || !username.trim().matches("[A-Za-z0-9_.-]{3,32}") ||
            name == null || name.trim().length() < 2 || name.trim().length() > 80 ||
            password == null || password.length() < 10 || password.length() > 128) return false;
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            if (hasAccounts()) return false;
            String id=java.util.UUID.randomUUID().toString();
            String encoded=encodeSecret(password);
            String[] parts=encoded.split("\\$");
            if(parts.length!=4) return false;
            ContentValues user=new ContentValues();
            user.put("id",id); user.put("username",username.trim()); user.put("display_name",name.trim());
            user.put("role","OWNER"); user.putNull("pin_hash"); user.put("password_hash",encoded); user.put("is_active",1);
            db.insertOrThrow("app_users",null,user);
            ContentValues pending=new ContentValues();
            pending.put("id",id); pending.put("username",username.trim()); pending.put("display_name",name.trim());
            pending.put("password_salt",parts[2]); pending.put("password_hash",parts[3]); pending.put("password_iterations",Integer.parseInt(parts[1]));
            db.insertOrThrow("owner_enrollment_queue",null,pending);
            JSONObject account=new JSONObject();
            account.put("id",id); account.put("username",username.trim()); account.put("display_name",name.trim());
            account.put("role","OWNER"); account.put("pin_hash",JSONObject.NULL); account.put("password_hash",encoded); account.put("is_active",1);
            enqueueSync(db,"app_user",id,"INSERT",account);
            db.setTransactionSuccessful(); return true;
        } catch(Exception error) { return false; } finally { db.endTransaction(); }
    }
    synchronized String pendingOwnerEnrollment() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,username,display_name,password_salt,password_hash,password_iterations FROM owner_enrollment_queue ORDER BY created_at LIMIT 1",null)) {
            if(!c.moveToFirst()) return null;
            JSONObject out=new JSONObject(); out.put("id",c.getString(0)); out.put("username",c.getString(1));
            out.put("display_name",c.getString(2)); out.put("password_salt",c.getString(3));
            out.put("password_hash",c.getString(4)); out.put("password_iterations",c.getInt(5)); return out.toString();
        } catch(Exception e) { return null; }
    }
    synchronized void finishOwnerEnrollment(String id,boolean success) {
        if(success) getWritableDatabase().delete("owner_enrollment_queue","id=?",new String[]{id});
    }

    synchronized String accountDiagnostics(){
        JSONObject out=new JSONObject();
        try{
            java.io.File file=context.getDatabasePath(DB_NAME);
            SQLiteDatabase database=getReadableDatabase();
            boolean table=false;
            try(Cursor c=database.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='app_users'",null)){table=c.moveToFirst();}
            int total=0,owners=0,cashiers=0;
            if(table){
                try(Cursor c=database.rawQuery("SELECT COUNT(*),COALESCE(SUM(CASE WHEN role='OWNER' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN role='CASHIER' THEN 1 ELSE 0 END),0) FROM app_users",null)){
                    if(c.moveToFirst()){total=c.getInt(0);owners=c.getInt(1);cashiers=c.getInt(2);}
                }
            }
            out.put("databaseFilePresent",file.exists());
            out.put("databaseVersion",database.getVersion());
            out.put("accountsTablePresent",table);
            out.put("accountCount",total);
            out.put("ownerCount",owners);
            out.put("cashierCount",cashiers);
            out.put("diagnostic","ok");
        }catch(Exception e){
            try{out.put("diagnostic","error");out.put("errorType",e.getClass().getSimpleName());}catch(Exception ignored){}
        }
        return out.toString();
    }
    synchronized boolean createCashier(String username,String name,String pin,String ownerId){
        if(!"OWNER".equals(activeRole)||activeUserId==null||!activeUserId.equals(ownerId)||username==null||username.trim().length()<3||name==null||name.trim().isEmpty()||pin==null||!pin.matches("[0-9]{4,8}"))return false;
        String id=java.util.UUID.randomUUID().toString(); String pinHash=encodeSecret(pin);
        ContentValues v=new ContentValues();v.put("id",id);v.put("username",username.trim());v.put("display_name",name.trim());v.put("role","CASHIER");v.put("pin_hash",pinHash);v.put("is_active",1);
        SQLiteDatabase db=getWritableDatabase();
        try{
            if(db.insertOrThrow("app_users",null,v)==-1)return false;
            JSONObject account=new JSONObject();account.put("id",id);account.put("username",username.trim());
            account.put("display_name",name.trim());account.put("role","CASHIER");account.put("pin_hash",pinHash);
            account.put("password_hash",JSONObject.NULL);account.put("is_active",1);
            db.beginTransaction();try{enqueueSync(db,"app_user",id,"INSERT",account);db.setTransactionSuccessful();}finally{db.endTransaction();}
            return true;
        }catch(Exception e){return false;}
    }
    synchronized String listCashiers(String ownerId){
        if(!"OWNER".equals(activeRole)||activeUserId==null||!activeUserId.equals(ownerId))return "[]";
        JSONArray out=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,username,display_name,is_active FROM app_users WHERE role='CASHIER' AND is_active=1 ORDER BY display_name COLLATE NOCASE",null)){
            while(c.moveToNext()){
                JSONObject row=new JSONObject();
                row.put("id",c.getString(0));row.put("username",c.getString(1));row.put("displayName",c.getString(2));row.put("active",c.getInt(3)==1);
                out.put(row);
            }
        }catch(Exception ignored){}
        return out.toString();
    }
    synchronized boolean removeCashier(String cashierId,String ownerId){
        if(!"OWNER".equals(activeRole)||activeUserId==null||!activeUserId.equals(ownerId)||cashierId==null||cashierId.trim().isEmpty())return false;
        SQLiteDatabase db=getWritableDatabase();
        try{
            ContentValues values=new ContentValues();values.put("is_active",0);
            int changed=db.update("app_users",values,"id=? AND role='CASHIER' AND is_active=1",new String[]{cashierId});
            if(changed==1){
                ContentValues audit=new ContentValues();audit.put("id",java.util.UUID.randomUUID().toString());audit.put("actor_id",ownerId);
                audit.put("action","CASHIER_REMOVED");audit.put("entity_type","app_user");audit.put("entity_id",cashierId);
                audit.put("details_json","{}");db.insert("audit_log",null,audit);
                try(Cursor c=db.rawQuery("SELECT id,username,display_name,role,pin_hash,password_hash,is_active FROM app_users WHERE id=?",new String[]{cashierId})){
                    if(c.moveToFirst()){
                        JSONObject account=new JSONObject();account.put("id",c.getString(0));account.put("username",c.getString(1));
                        account.put("display_name",c.getString(2));account.put("role",c.getString(3));
                        account.put("pin_hash",c.isNull(4)?JSONObject.NULL:c.getString(4));
                        account.put("password_hash",c.isNull(5)?JSONObject.NULL:c.getString(5));account.put("is_active",c.getInt(6));
                        db.beginTransaction();try{enqueueSync(db,"app_user",cashierId,"UPDATE",account);db.setTransactionSuccessful();}finally{db.endTransaction();}
                    }
                }
                return true;
            }
        }catch(Exception ignored){}
        return false;
    }
    synchronized String ownerPasswordVerifier(String newPassword) {
        if (!isOwnerSession() || newPassword == null || newPassword.length() < 10 || newPassword.length() > 128) return null;
        return encodeSecret(newPassword);
    }

    synchronized String activeOwnerUsername() {
        if (!isOwnerSession() || activeUserId == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT username FROM app_users WHERE id=? AND role='OWNER' AND is_active=1", new String[]{activeUserId})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    synchronized boolean applyOwnerPasswordVerifier(String verifier) {
        if (!isOwnerSession() || activeUserId == null || verifier == null) return false;
        String[] parts = verifier.split("\\$", 4);
        if (parts.length != 4 || !"pbkdf2".equals(parts[0]) || !"120000".equals(parts[1]) ||
            !parts[2].matches("[a-fA-F0-9]{32}") || !parts[3].matches("[a-fA-F0-9]{64}")) return false;
        ContentValues values = new ContentValues();
        values.put("password_hash", verifier);
        return getWritableDatabase().update("app_users", values, "id=? AND role='OWNER' AND is_active=1", new String[]{activeUserId}) == 1;
    }

    synchronized boolean importRemoteAccount(String raw) {
        try {
            JSONObject account = new JSONObject(raw);
            String id=account.optString("id",""), username=account.optString("username",""), role=account.optString("role","");
            String name=account.optString("display_name",account.optString("displayName",""));
            if(id.isEmpty() || !username.matches("[A-Za-z0-9_.-]{3,32}") ||
               !("OWNER".equals(role) || "CASHIER".equals(role)) || name.length()<2 ||
               account.optInt("is_active",0)!=1) return false;
            ContentValues values=new ContentValues();
            values.put("id",id); values.put("username",username); values.put("display_name",name);
            values.put("role",role); values.put("is_active",1);
            if(account.isNull("pin_hash")) values.putNull("pin_hash"); else values.put("pin_hash",account.optString("pin_hash"));
            if(account.isNull("password_hash")) values.putNull("password_hash"); else values.put("password_hash",account.optString("password_hash"));
            SQLiteDatabase database=getWritableDatabase();
            int changed=database.update("app_users",values,"id=?",new String[]{id});
            if(changed==0) database.insertWithOnConflict("app_users",null,values,SQLiteDatabase.CONFLICT_REPLACE);
            String loginKey=username.toLowerCase(Locale.ROOT); failedAttempts.remove(loginKey); lockedUntil.remove(loginKey);
            return true;
        } catch(Exception e) { return false; }
    }

    synchronized String authenticate(String username,String secret){
        if(username==null||secret==null)return null;String key=username.trim().toLowerCase(Locale.ROOT);long now=System.currentTimeMillis();
        Long until=lockedUntil.get(key);if(until!=null&&until>now)return null;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,display_name,role,pin_hash,password_hash,is_active FROM app_users WHERE username=? COLLATE NOCASE",new String[]{username.trim()})){
            if(!c.moveToFirst()||c.getInt(5)!=1)return failedLogin(key);
            String role=c.getString(2),stored="OWNER".equals(role)?c.getString(4):c.getString(3);
            if(!verifySecret(secret,stored))return failedLogin(key);
            String id=c.getString(0),name=c.getString(1);
            if(stored!=null&&!stored.startsWith("pbkdf2$")){ContentValues v=new ContentValues();v.put("OWNER".equals(role)?"password_hash":"pin_hash",encodeSecret(secret));getWritableDatabase().update("app_users",v,"id=?",new String[]{id});}
            failedAttempts.remove(key);lockedUntil.remove(key);activeUserId=id;activeRole=role;
            org.json.JSONObject user=new org.json.JSONObject();user.put("id",id);user.put("displayName",name);user.put("role",role);return user.toString();
        }catch(Exception e){return null;}
    }
    private String failedLogin(String key){int n=failedAttempts.containsKey(key)?failedAttempts.get(key)+1:1;if(n>=5){lockedUntil.put(key,System.currentTimeMillis()+30000L);failedAttempts.remove(key);}else failedAttempts.put(key,n);return null;}
    synchronized boolean isOwnerSession(){return activeUserId!=null && "OWNER".equals(activeRole);}
    synchronized void logout(){activeUserId=null;activeRole=null;}
    synchronized String loadAppState(){try(Cursor c=getReadableDatabase().rawQuery("SELECT state_json FROM app_state WHERE state_key='main'",null)){return c.moveToFirst()?c.getString(0):null;}}
    synchronized boolean saveAppState(String json){
        if(activeUserId==null||json==null||json.length()>2000000)return false;
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            String previous=loadAppState();
            enqueueStateDiff(db,previous,json);
            ContentValues v=new ContentValues();v.put("state_key","main");v.put("state_json",json);
            boolean ok=db.insertWithOnConflict("app_state",null,v,SQLiteDatabase.CONFLICT_REPLACE)!=-1;
            if(ok)db.setTransactionSuccessful();
            return ok;
        }catch(Exception e){return false;}finally{db.endTransaction();}
    }
    private static JSONObject objectOrEmpty(String raw){try{return raw==null?new JSONObject():new JSONObject(raw);}catch(Exception e){return new JSONObject();}}
    private static String entityKey(JSONObject item,String type){
        String key=item.optString("id","");
        if(key.isEmpty()&&"sale".equals(type))key=item.optString("receiptNo","");
        return key;
    }
    private void enqueueStateDiff(SQLiteDatabase db,String oldJson,String newJson){
        JSONObject before=objectOrEmpty(oldJson),after=objectOrEmpty(newJson);
        diffArray(db,"product",before.optJSONArray("products"),after.optJSONArray("products"));
        diffArray(db,"sale",before.optJSONArray("sales"),after.optJSONArray("sales"));
        diffArray(db,"movement",before.optJSONArray("movements"),after.optJSONArray("movements"));
        JSONObject oldSettings=new JSONObject(),newSettings=new JSONObject();
        try{oldSettings.put("exchangeRate",before.optDouble("exchangeRate",28));newSettings.put("exchangeRate",after.optDouble("exchangeRate",28));}catch(Exception ignored){}
        if(!oldSettings.toString().equals(newSettings.toString()))enqueueSync(db,"settings","main","UPDATE",newSettings);
    }
    private void diffArray(SQLiteDatabase db,String type,JSONArray oldArray,JSONArray newArray){
        Map<String,JSONObject> oldMap=new HashMap<>(),newMap=new HashMap<>();
        if(oldArray!=null)for(int i=0;i<oldArray.length();i++){JSONObject o=oldArray.optJSONObject(i);if(o!=null){String k=entityKey(o,type);if(!k.isEmpty())oldMap.put(k,o);}}
        if(newArray!=null)for(int i=0;i<newArray.length();i++){JSONObject o=newArray.optJSONObject(i);if(o!=null){String k=entityKey(o,type);if(!k.isEmpty())newMap.put(k,o);}}
        for(Map.Entry<String,JSONObject> e:newMap.entrySet()){
            JSONObject old=oldMap.get(e.getKey()),now=e.getValue();
            if(old==null)enqueueSync(db,type,e.getKey(),"INSERT",now);
            else if(!old.toString().equals(now.toString()))enqueueSync(db,type,e.getKey(),"UPDATE",now);
        }
        for(Map.Entry<String,JSONObject> e:oldMap.entrySet())if(!newMap.containsKey(e.getKey()))enqueueSync(db,type,e.getKey(),"DELETE",new JSONObject());
    }
    private void enqueueSync(SQLiteDatabase db,String type,String entityId,String requested,JSONObject payload){
        try(Cursor c=db.rawQuery("SELECT id,base_version FROM sync_queue WHERE entity_type=? AND entity_id=? AND synced_at IS NULL LIMIT 1",new String[]{type,entityId})){
            if(c.moveToFirst()){
                String existingId=c.getString(0);int base=c.getInt(1);
                if("DELETE".equals(requested)&&base==0){db.delete("sync_queue","id=?",new String[]{existingId});return;}
                ContentValues v=new ContentValues();v.put("operation",base==0?"INSERT":requested);v.put("payload_json",payload.toString());v.put("blocked",0);v.putNull("last_error");
                db.update("sync_queue",v,"id=?",new String[]{existingId});return;
            }
        }
        int base=0;
        try(Cursor c=db.rawQuery("SELECT version FROM sync_versions WHERE entity_type=? AND entity_id=?",new String[]{type,entityId})){if(c.moveToFirst())base=c.getInt(0);}
        if("DELETE".equals(requested)&&base==0)return;
        ContentValues v=new ContentValues();v.put("id",java.util.UUID.randomUUID().toString());v.put("entity_type",type);v.put("entity_id",entityId);
        v.put("operation",base==0?"INSERT":requested);v.put("payload_json",payload.toString());v.put("base_version",base);v.put("blocked",0);
        db.insertOrThrow("sync_queue",null,v);
    }
    synchronized void queueCurrentStateForSync(){
        if(!"OWNER".equals(activeRole))return;
        SQLiteDatabase db=getWritableDatabase();
        try(Cursor c=db.rawQuery("SELECT state_json FROM app_state WHERE state_key='main'",null)){
            if(c.moveToFirst()){db.beginTransaction();try{enqueueStateDiff(db,null,c.getString(0));db.setTransactionSuccessful();}finally{db.endTransaction();}}
        }
    }
    synchronized String pendingSyncOperations(){
        JSONArray out=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,entity_type,entity_id,operation,base_version,payload_json FROM sync_queue WHERE synced_at IS NULL AND blocked=0 ORDER BY created_at LIMIT 100",null)){
            while(c.moveToNext()){
                JSONObject o=new JSONObject();o.put("operation_id",c.getString(0));o.put("entity_type",c.getString(1));o.put("entity_id",c.getString(2));
                o.put("operation",c.getString(3));o.put("base_version",c.getInt(4));o.put("payload",new JSONObject(c.getString(5)));out.put(o);
            }
        }catch(Exception ignored){}
        return out.toString();
    }
    synchronized void finishSyncOperation(String id,String status,int version,String reason,String sentPayload){
        SQLiteDatabase db=getWritableDatabase();
        if("APPLIED".equals(status)){
            try(Cursor c=db.rawQuery("SELECT entity_type,entity_id,payload_json,operation FROM sync_queue WHERE id=?",new String[]{id})){
                if(c.moveToFirst()){
                    String type=c.getString(0),entity=c.getString(1),currentPayload=c.getString(2),operation=c.getString(3);
                    ContentValues v=new ContentValues();v.put("entity_type",type);v.put("entity_id",entity);v.put("version",version);
                    db.insertWithOnConflict("sync_versions",null,v,SQLiteDatabase.CONFLICT_REPLACE);
                    boolean unchanged=false;
                    try{unchanged=canonicalJson(new JSONObject(currentPayload)).equals(canonicalJson(new JSONObject(sentPayload)));}catch(Exception ignored){}
                    if(unchanged)db.delete("sync_queue","id=?",new String[]{id});
                    else{
                        ContentValues pending=new ContentValues();pending.put("id",java.util.UUID.randomUUID().toString());pending.put("base_version",version);
                        pending.put("operation","DELETE".equals(operation)?"DELETE":"UPDATE");pending.put("blocked",0);pending.putNull("last_error");
                        db.update("sync_queue",pending,"id=?",new String[]{id});
                    }
                }
            }
        }else if("CONFLICT".equals(status)){
            ContentValues v=new ContentValues();v.put("blocked",1);v.put("last_error",reason==null?"Conflict detected":reason);
            db.update("sync_queue",v,"id=?",new String[]{id});
        }else{
            db.execSQL("UPDATE sync_queue SET attempts=attempts+1,last_error=? WHERE id=?",new Object[]{reason,id});
        }
    }
    private static String canonicalJson(JSONObject object){
        java.util.TreeSet<String> keys=new java.util.TreeSet<>();
        java.util.Iterator<String> iterator=object.keys();while(iterator.hasNext())keys.add(iterator.next());
        StringBuilder out=new StringBuilder("{");boolean first=true;
        for(String key:keys){if(!first)out.append(',');first=false;out.append(JSONObject.quote(key)).append(':').append(canonicalValue(object.opt(key)));}
        return out.append('}').toString();
    }
    private static String canonicalValue(Object value){
        if(value==null||value==JSONObject.NULL)return "null";
        if(value instanceof JSONObject)return canonicalJson((JSONObject)value);
        if(value instanceof JSONArray){
            JSONArray array=(JSONArray)value;StringBuilder out=new StringBuilder("[");
            for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(canonicalValue(array.opt(i)));}
            return out.append(']').toString();
        }
        if(value instanceof String)return JSONObject.quote((String)value);
        return value.toString();
    }
    synchronized int pendingSyncCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM sync_queue WHERE synced_at IS NULL AND blocked=0",null)){return c.moveToFirst()?c.getInt(0):0;}}
    synchronized int syncConflictCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM sync_queue WHERE blocked=1",null)){return c.moveToFirst()?c.getInt(0):0;}}
    synchronized boolean isSyncEntityPending(String type,String id){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM sync_queue WHERE entity_type=? AND entity_id=? AND synced_at IS NULL LIMIT 1",new String[]{type,id})){return c.moveToFirst();}
    }
    synchronized void applyRemoteSyncRecords(String recordsJson){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            JSONArray records=new JSONArray(recordsJson);JSONObject state=objectOrEmpty(loadAppState());
            JSONArray products=state.optJSONArray("products");if(products==null)products=new JSONArray();
            JSONArray sales=state.optJSONArray("sales");if(sales==null)sales=new JSONArray();
            JSONArray movements=state.optJSONArray("movements");if(movements==null)movements=new JSONArray();
            for(int i=0;i<records.length();i++){
                JSONObject r=records.optJSONObject(i);if(r==null)continue;
                String type=r.optString("entity_type",""),id=r.optString("entity_id","");int version=r.optInt("version",0);
                if(id.isEmpty()||isSyncEntityPending(type,id))continue;
                // This method is called only after an authenticated Cloudflare device request.
                // Apply the shared shop snapshot even before local login imports the user record.
                boolean deleted=r.optBoolean("deleted",false);JSONObject payload=r.optJSONObject("payload");if(payload==null)payload=new JSONObject();
                if("product".equals(type))products=mergeArray(products,id,payload,deleted,"product");
                else if("sale".equals(type))sales=mergeArray(sales,id,payload,deleted,"sale");
                else if("movement".equals(type))movements=mergeArray(movements,id,payload,deleted,"movement");
                else if("settings".equals(type)&&!deleted)state.put("exchangeRate",payload.optDouble("exchangeRate",state.optDouble("exchangeRate",28)));
                else if("app_user".equals(type)) {
                    if(deleted) db.execSQL("UPDATE app_users SET is_active=0,updated_at=CURRENT_TIMESTAMP WHERE id=?",new Object[]{id});
                    else {
                        String role=payload.optString("role","");
                        if(("OWNER".equals(role)||"CASHIER".equals(role)) && !payload.optString("username","").isEmpty()) {
                            ContentValues user=new ContentValues();user.put("id",id);user.put("username",payload.optString("username"));
                            user.put("display_name",payload.optString("display_name"));user.put("role",role);
                            if(payload.isNull("pin_hash"))user.putNull("pin_hash");else user.put("pin_hash",payload.optString("pin_hash"));
                            if(payload.isNull("password_hash"))user.putNull("password_hash");else user.put("password_hash",payload.optString("password_hash"));
                            user.put("is_active",payload.optInt("is_active",1));
                            db.insertWithOnConflict("app_users",null,user,SQLiteDatabase.CONFLICT_REPLACE);
                        }
                    }
                }
                ContentValues v=new ContentValues();v.put("entity_type",type);v.put("entity_id",id);v.put("version",version);
                db.insertWithOnConflict("sync_versions",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            }
            if(activeUserId!=null) {
                state.put("products",products);state.put("sales",sales);state.put("movements",movements);
                ContentValues saved=new ContentValues();saved.put("state_key","main");saved.put("state_json",state.toString());
                db.insertWithOnConflict("app_state",null,saved,SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        }catch(Exception ignored){}finally{db.endTransaction();}
    }
    private JSONArray mergeArray(JSONArray source,String id,JSONObject payload,boolean deleted,String type){
        JSONArray out=new JSONArray();boolean found=false;
        for(int i=0;i<source.length();i++){
            JSONObject item=source.optJSONObject(i);if(item==null)continue;
            if(id.equals(entityKey(item,type))){found=true;if(!deleted)out.put(payload);}else out.put(item);
        }
        if(!found&&!deleted)out.put(payload);
        return out;
    }
}
