package ru.freeoffice.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.*;
import android.text.style.StyleSpan;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Offline alpha. Imported complex documents are never overwritten. */
public class MainActivity extends Activity {
 private final ExecutorService worker = Executors.newSingleThreadExecutor();
 private LinearLayout root, content;
 private TextView subtitle;
 private LinearLayout appHeader;
 private DocumentViewer documentViewer;
 private TextViewer textViewer;
 private DocxViewer docxViewer;
 private JSONObject structuredDocument;
 private String importWarning="";
 private int insetTop,insetBottom;
 private boolean compactViewer;
 private Button cancelOperation;
 private long operationGeneration=0;
 private EditText document;
 private JSONArray sheets = new JSONArray();
 private int sheetIndex = 0;
 private String mode = "home", fileName = "Документ.docx";
 private boolean dirty = false, loading = false;
 private String saveFormat = "docx";
 private File source;
 private String pendingMime, pendingName;
 private long revision = 0, exportRevision;
 private static final int OPEN = 10, SAVE = 11;
 private SheetViewer sheetViewer;
 private String viewerSession;
 private PdfViewer pdfViewer;
 private Uri originalUri;
 private String workingFormat="docx", saveState="", plainText="";
 private JSONObject plainSettings=new JSONObject();
 private boolean originPlainTable=false, exportComplete=false, savingOperation=false;
 private boolean draftDismissed=false;
 private final Handler draftHandler=new Handler(Looper.getMainLooper());
 private final Runnable draftTask=()->persistDraft();
 private static final String RECOVERY_KEY="editorRecovery";
 private static final int MAX_DRAFT_BYTES=16*1024*1024;
 private static final Object DRAFT_LOCK=new Object();
 private static long draftGeneration=0;
 private String recoveryToken;
 private final android.util.AtomicFile draftFile(){return new android.util.AtomicFile(new File(getFilesDir(),"working-draft.json"));}
 private static final long MAX_FILE = 32L * 1024 * 1024;
 private final int BLUE = Color.rgb(36,92,216), INK = Color.rgb(20,36,59);
 private JSONObject obj() { return new JSONObject(); }
 private JSONObject put(JSONObject o, String k, Object v) { try { o.put(k,v); } catch(JSONException e) { throw new IllegalArgumentException(e); } return o; }
 private int dp(int n) { return (int)(getResources().getDisplayMetrics().density*n+.5f); }
 @Override public void onCreate(Bundle state) {
  super.onCreate(state);
  getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  shell(); home();
  Object retained=getLastNonConfigurationInstance();
  if(retained instanceof String){applyEditorSnapshot((String)retained);return;}
  if(state!=null&&state.containsKey(RECOVERY_KEY)){restoreLifecycleDraft(state.getString(RECOVERY_KEY));return;}
  if (getIntent().getData()!=null) openUri(getIntent().getData());
 }
 private void shell() {
  root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(244,246,250));
  root.setPadding(dp(16),dp(12),dp(16),dp(8));
  // Insets are needed with target SDK 35 edge-to-edge enforcement.
  root.setOnApplyWindowInsetsListener((v,insets)-> { insetTop=insets.getSystemWindowInsetTop();insetBottom=insets.getSystemWindowInsetBottom();applyRootPadding(); return insets.consumeSystemWindowInsets(); });
  setContentView(root);
  LinearLayout header=new LinearLayout(this); appHeader=header; header.setGravity(Gravity.CENTER_VERTICAL);
  TextView title=text("Свободный офис",23,INK); title.setTypeface(null,Typeface.BOLD);
  header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
  Button about=button("⋮",v->{PopupMenu menu=new PopupMenu(this,v);menu.getMenu().add("О проекте");menu.getMenu().add("Возможности по форматам");menu.setOnMenuItemClickListener(i->{if(i.getTitle().toString().equals("О проекте"))about();else formatInfo();return true;});menu.show();});about.setContentDescription("Меню приложения");header.addView(about);
  cancelOperation=button("Отмена",v->{operationGeneration++;busy(false);saveState="Открытие отменено · исходник не изменён";subtitle.setText(saveState);});cancelOperation.setVisibility(View.GONE);header.addView(cancelOperation);
  root.addView(header);
  subtitle=text("Без подписки · Без рекламы · Без интернета",12,Color.DKGRAY); root.addView(subtitle);
  content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
 }
 private void applyRootPadding(){root.setPadding(compactViewer?0:dp(16),insetTop+(compactViewer?0:dp(12)),compactViewer?0:dp(16),insetBottom+(compactViewer?0:dp(8)));}
 private void viewerChrome(boolean compact){compactViewer=compact;if(appHeader!=null)appHeader.setVisibility(compact?View.GONE:View.VISIBLE);subtitle.setVisibility(compact?View.GONE:View.VISIBLE);applyRootPadding();}
 private void closeDocumentViewer(){if(documentViewer!=null){documentViewer.close();documentViewer=null;}if(textViewer!=null){textViewer.close();textViewer=null;}if(docxViewer!=null){content.removeView(docxViewer);docxViewer=null;}}
 private void importAgain(){
  if(source==null)return;final File local=source;final String ext=workingFormat;
  PlainFileUi.chooseImport(this,local,ext,(job,done)->background(job,done),parsed->{
   closeSheetViewer();closeDocumentViewer();plainSettings=metadataSettings(parsed);importWarning=parsed.optString("warning","");
   if(ext.equals("txt")){plainText=parsed.optString("text");showTextViewer();}else{sheets=parsed.optJSONArray("sheets");sheetIndex=0;showMemorySheetViewer();}
  },()->{});
 }
 private void rememberFile(Uri uri,String name){
  try {getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
  try{JSONArray old=new JSONArray(getPreferences(0).getString("recentFiles","[]")),out=new JSONArray();out.put(put(put(obj(),"uri",uri.toString()),"name",name));for(int i=0;i<old.length()&&out.length()<12;i++){JSONObject v=old.optJSONObject(i);if(v!=null&&!uri.toString().equals(v.optString("uri")))out.put(v);}getPreferences(0).edit().putString("recentFiles",out.toString()).apply();}catch(Exception ignored){}
 }
 private TextView text(String s,int size,int color) { TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setPadding(0,dp(7),0,dp(7)); return t; }
 private Button button(String s,View.OnClickListener click) { Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(BLUE); b.setOnClickListener(click); return b; }
 private void action(String s,Runnable r) { content.addView(button(s,v->r.run()),new LinearLayout.LayoutParams(-1,dp(56))); }
 private void message(String s) { if (!isFinishing()) new AlertDialog.Builder(this).setTitle("Свободный офис").setMessage(s).setPositiveButton("Понятно",null).show(); }
 private void changed() { dirty=true; revision++;saveState="Изменено · исходник не перезаписывается";subtitle.setText(saveState);draftHandler.removeCallbacks(draftTask);draftHandler.postDelayed(draftTask,700); }
 private void home() {
  if(loading)return;
  closeSheetViewer();
  closePdf();closeDocumentViewer();viewerChrome(false); mode="home"; dirty=false;saveState=""; content.removeAllViews(); subtitle.setText("Без подписки · Без рекламы · Без интернета");
  content.addView(text("Документы на устройстве",22,INK));
  
  action("Открыть файл",()->chooseOpen());
  action("Создать…",()->new AlertDialog.Builder(this).setTitle("Новый файл").setItems(new String[]{"Документ DOCX","Текст TXT","Таблица XLSX"},(d,n)->startWorkingCopy(()->{
   if(loading)return;originalUri=null;originPlainTable=false;saveState="";
   if(n==0){fileName="Документ.docx";workingFormat=saveFormat="docx";showDocument(new JSONArray());}
   else if(n==1){fileName="Текст.txt";workingFormat=saveFormat="txt";plainSettings=new JSONObject();showTextEditor("");}
   else{fileName="Таблица.xlsx";workingFormat=saveFormat="xlsx";sheets=new JSONArray();sheets.put(put(put(obj(),"name","Лист1"),"rows",new JSONArray().put(new JSONArray().put(""))));sheetIndex=0;showSheet();}
  })).show());
  if(!draftDismissed&&draftFile().getBaseFile().exists())action("Восстановить локальный черновик",()->restoreDraft());
  content.addView(text("0.2.6 · кандидат для DroidDeck Tester",12,BLUE));
  try{JSONArray recent=new JSONArray(getPreferences(0).getString("recentFiles","[]"));if(recent.length()>0)content.addView(text("Недавние файлы",18,INK));for(int i=0;i<recent.length();i++){JSONObject f=recent.getJSONObject(i);final String uri=f.getString("uri");action(f.optString("name","Файл"),()->openUri(Uri.parse(uri)));}}catch(Exception ignored){}
  content.addView(text("Офлайн · исходники не перезаписываются. Возможности и ограничения форматов — в меню ⋮.",14,Color.DKGRAY));
  LinearLayout items=new LinearLayout(this);items.setOrientation(LinearLayout.VERTICAL);
  while(content.getChildCount()>0){View child=content.getChildAt(0);content.removeViewAt(0);items.addView(child);}
  ScrollView scroll=new ScrollView(this);scroll.addView(items);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
 }
 private void formatInfo() { message("ВОЗМОЖНОСТИ ЭТОЙ ALPHA\n\nDOCX: открытие, базовое редактирование текста, сохранение DOCX/ODT. Сложная вёрстка не сохраняется.\n\nODT: импорт текста, редактирование, сохранение простого ODT/DOCX.\n\nXLSX: импорт данных и поддерживаемых формул, редактирование, экспорт XLSX/ODS/CSV/TSV. Оформление не сохраняется.\n\nXLS: просмотр больших листов; редактирование упрощённой копии в пределах редактора; сохранения XLS нет.\n\nODS: импорт значений, редактирование, экспорт простого ODS/XLSX/CSV/TSV; без вычисления ODF-формул.\n\nTXT: чтение и редактирование простого текста; копия с выбором кодировки, BOM и окончаний строк. Лимит 2 000 000 символов.\n\nCSV/TSV: импорт с выбором кодировки и разделителя; все поля — строки. Сохранение копии текущего листа с настройками. Лимиты: 20 000 строк, 512 столбцов, 500 000 ячеек, 2 000 000 символов. Конвертация в XLSX ограничена 2000×200 и 100 000 ячеек.\n\nPDF: просмотр страниц, pinch и +/−, по ширине, переход к странице; без редактирования, OCR и экспорта.\n\nPPTX/ODP: извлечение и чтение текста слайдов. Не визуальный просмотр; нет редактирования и сохранения презентаций."); }
 private void startWorkingCopy(Runnable next) {
  if(loading)return;
  if(!dirty&&!draftDismissed&&draftFile().getBaseFile().exists()){
   new AlertDialog.Builder(this).setTitle("Есть локальный черновик").setMessage("Хранится один рабочий черновик. Восстановите его либо явно начните новую копию: прежний черновик будет удалён. Исходные документы не изменяются.").setNegativeButton("Отмена",null).setNeutralButton("Восстановить",(d,w)->restoreDraft()).setPositiveButton("Начать новую копию",(d,w)->{clearDraft();next.run();}).show();
  }else next.run();
 }
 private boolean sameDocument(Uri a,Uri b) {
  if(a==null||b==null)return false;if(a.equals(b))return true;
  try{return Objects.equals(a.getAuthority(),b.getAuthority())&&android.provider.DocumentsContract.getDocumentId(a).equals(android.provider.DocumentsContract.getDocumentId(b));}catch(Exception e){return false;}
 }
 private void confirmDiscard(Runnable next) {
  if(loading) return;
  if(!dirty) { next.run(); return; }
  new AlertDialog.Builder(this).setTitle("Есть несохранённые изменения").setMessage("Выйти без сохранения? Чтобы сохранить файл, нажмите «Отмена», затем «Сохранить копию». Локальный черновик будет удалён только при явном выходе без сохранения.").setNegativeButton("Отмена",null).setPositiveButton("Выйти без сохранения",(d,w)->{clearDraft();next.run();}).show();
 }
 private void chooseOpen() {
  confirmDiscard(()-> { Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("*/*"); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,OPEN); });
 }
 private String name(Uri uri) {
  try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) { if(c!=null&&c.moveToFirst()) return c.getString(0); } catch(Exception ignored) {}
  return "Файл";
 }
 private void busy(boolean on) { loading=on;if(cancelOperation!=null)cancelOperation.setVisibility(on&&!savingOperation?View.VISIBLE:View.GONE);subtitle.setText(on?(savingOperation?"Подготовка / сохранение копии…":"Обработка файла…"):(!saveState.isEmpty()?saveState:(dirty?"Изменено":"Просмотр · исходник не изменяется"))); }
 private void background(Callable<JSONObject> job, java.util.function.Consumer<JSONObject> done) {
  if(loading)return;
  final long token=++operationGeneration;
  busy(true); worker.execute(()-> { JSONObject result; try { result=job.call(); } catch(Throwable e) { result=put(put(obj(),"ok",false),"error",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()); }
   final JSONObject out=result; runOnUiThread(()-> {if(isDestroyed()||token!=operationGeneration){if(out.has("session")&&!worker.isShutdown())worker.execute(()->{try{nativeCall(put(put(obj(),"op","sheet_view_close"),"session",out.optString("session")));}catch(Exception ignored){}});return;}busy(false);if(!out.optBoolean("ok")){boolean wasSaving=savingOperation;if(savingOperation){savingOperation=false;saveState="Не удалось сохранить · изменения остаются в редакторе";subtitle.setText(saveState);}message("Не удалось выполнить операцию.\n"+out.optString("error","Неизвестная ошибка")+(wasSaving?"\nНовая копия могла остаться неполной. Изменения остаются в редакторе.":""));}else done.accept(out); });
  });
 }
 private JSONObject nativeCall(JSONObject req) throws Exception { String response=NativeBridge.call(req.toString()); if(response==null)throw new IOException("Объём данных превышает защитный лимит моста или недостаточно памяти. Исходник не изменён."); return new JSONObject(response); }
 private void openUri(Uri uri) {
  if(loading)return;
  final String selectedName=name(uri); String ext=selectedName.toLowerCase(Locale.ROOT);
  final String extension=ext.contains(".")?ext.substring(ext.lastIndexOf('.')+1):"";
  if(!Arrays.asList("docx","odt","xls","xlsx","ods","csv","tsv","txt","pdf","pptx","odp").contains(extension)) { message("Доступны DOCX, ODT, XLS, XLSX, ODS, CSV, TSV, TXT, PDF и текст слайдов PPTX/ODP. DOC/PPT/XLSB и макросы не поддерживаются. У файла должно быть соответствующее расширение."); return; }
  background(()-> {
   File temp=File.createTempFile("input-",ext.substring(ext.lastIndexOf('.')),getCacheDir());
   try(InputStream in=getContentResolver().openInputStream(uri); OutputStream out=new FileOutputStream(temp)) { if(in==null)throw new IOException("Нет доступа к файлу"); byte[] buf=new byte[16384]; long count=0; int n; while((n=in.read(buf))!=-1){ count+=n; if(count>MAX_FILE)throw new IOException("В этой версии размер файла ограничен 32 МБ"); out.write(buf,0,n); } }
   JSONObject result;
   if(Arrays.asList("txt","csv","tsv").contains(extension)) {result=put(put(obj(),"ok",true),"plainPending",true);}
   else if(extension.equals("pdf")) { result=put(obj(),"ok",true); }
   else if(extension.equals("xls")||extension.equals("xlsx")) result=nativeCall(put(put(obj(),"op","sheet_view_open"),"path",temp.getAbsolutePath()));
   else if(Arrays.asList("odt","ods","csv","tsv","pptx","odp").contains(extension)) result=ModernFormats.open(temp,extension);
   else if(extension.equals("docx")) result=openDocx(temp);
   else result=nativeCall(put(put(obj(),"op","sheet_open"),"path",temp.getAbsolutePath()));
   return put(put(result,"localPath",temp.getAbsolutePath()),"originalName",selectedName);
  },res->{
   if(res.optBoolean("plainPending")){File local=new File(res.optString("localPath"));PlainFileUi.chooseImport(this,local,extension,(job,done)->background(job,done),parsed->{closeSheetViewer();closePdf();closeDocumentViewer();rememberFile(uri,selectedName);source=local;originalUri=uri;fileName=selectedName;dirty=false;saveState="";workingFormat=saveFormat=extension;plainSettings=metadataSettings(parsed);importWarning=parsed.optString("warning","");originPlainTable=!extension.equals("txt");if(extension.equals("txt")){plainText=parsed.optString("text");showTextViewer();}else{sheets=parsed.optJSONArray("sheets");sheetIndex=0;showMemorySheetViewer();}},()->{});return;}
   closeSheetViewer(); closePdf();closeDocumentViewer();rememberFile(uri,selectedName);structuredDocument=null;importWarning=""; source=new File(res.optString("localPath")); originalUri=uri;fileName=selectedName; dirty=false;saveState="";originPlainTable=false;plainSettings=new JSONObject();
   if(extension.equals("docx")){workingFormat=saveFormat="docx";showDocxViewer(res);}
   else if(extension.equals("odt")){workingFormat=saveFormat="odt";showDocumentViewer(res.optJSONArray("paragraphs"));}
   else if(extension.equals("pdf"))showPdf();
   else if(extension.equals("pptx")||extension.equals("odp")){showSlides(res.optJSONArray("slides"));if(res.optBoolean("fallbackOrder"))message("Порядок слайдов не удалось восстановить точно. Показан резервный порядок файлов; не полагайтесь на нумерацию слайдов этой презентации.");}
   else if(extension.equals("xls")||extension.equals("xlsx")){workingFormat=saveFormat="xlsx";showNativeSheetViewer(res);}
   else { workingFormat=saveFormat=extension; sheets=res.optJSONArray("sheets"); if(sheets==null||sheets.length()==0){message("В книге нет доступных листов");home();return;} sheetIndex=0;showMemorySheetViewer(); }
  });
 }
 private void closeSheetViewer() {
  if(sheetViewer!=null){sheetViewer.close();sheetViewer=null;}
  final String session=viewerSession;viewerSession=null;
  if(session!=null&&!worker.isShutdown())worker.execute(()->{try{nativeCall(put(put(obj(),"op","sheet_view_close"),"session",session));}catch(Exception ignored){}});
 }
 private JSONObject openDocx(File file) throws Exception {
  JSONObject result=nativeCall(put(put(obj(),"op","docx_view"),"path",file.getAbsolutePath()));
  if(!result.optBoolean("ok"))throw new IOException(result.optString("error","Не удалось открыть DOCX"));
  return result;
 }
 private JSONObject metadataSettings(JSONObject from) {
  JSONObject out=obj();if(from!=null)for(String key:new String[]{"encoding","delimiter","newline","bom","neutralizeFormulas"})if(from.has(key))put(out,key,from.opt(key));return out;
 }
 private void showTextViewer() {
  closeDocumentViewer();mode="txtview";viewerChrome(true);
  textViewer=new TextViewer(this,content,plainText,fileName,put(metadataSettings(plainSettings),"warning",importWarning),()->home(),()->startWorkingCopy(()->{closeDocumentViewer();showTextEditor(plainText);}),()->importAgain());textViewer.start();
 }
 private void showTextEditor(String value) {
  closeDocumentViewer();viewerChrome(false);mode="txt";content.removeAllViews();toolbar();document=new EditText(this);document.setTextSize(18);document.setTextColor(INK);document.setBackgroundColor(Color.WHITE);document.setGravity(Gravity.TOP);document.setPadding(dp(12),dp(12),dp(12),dp(48));document.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);document.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2_000_000)});document.setText(value);document.addTextChangedListener(watcher(()->changed()));ScrollView scroll=new ScrollView(this);scroll.addView(document,new ScrollView.LayoutParams(-1,-2));content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));content.addView(text("TXT · без форматирования · кодировка и переводы строк выбираются при сохранении копии",12,Color.DKGRAY));
 }
 private JSONObject editorSnapshot() {
  if(!(mode.equals("docx")||mode.equals("txt")||mode.equals("sheet"))||document==null&& !mode.equals("sheet"))return null;
  JSONObject draft=put(put(put(put(put(put(obj(),"mode",mode),"name",fileName),"format",workingFormat),"settings",metadataSettings(plainSettings)),"plainTable",originPlainTable),"sheet",sheetIndex);
  if(originalUri!=null)put(draft,"originalUri",originalUri.toString());
  put(draft,"dirty",dirty);put(draft,"revision",revision);put(draft,"exportRevision",exportRevision);put(draft,"exportComplete",exportComplete);put(draft,"saveFormat",saveFormat);
  if(document!=null&&!mode.equals("sheet")){put(draft,"selectionStart",document.getSelectionStart());put(draft,"selectionEnd",document.getSelectionEnd());}
  if(mode.equals("sheet"))put(draft,"sheets",sheets);else if(mode.equals("txt"))put(draft,"text",document.getText().toString());else put(draft,"paragraphs",paragraphs());
  return draft;
 }
 private String snapshotData() {
  JSONObject draft=editorSnapshot();if(draft==null)return null;
  if(recoveryToken==null)recoveryToken=UUID.randomUUID().toString();
  put(draft,"recoveryToken",recoveryToken);
  String data=draft.toString();
  if(data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_DRAFT_BYTES){
   subtitle.setText("Черновик больше 16 МБ · перед выходом сохраните копию");return null;
  }
  return data;
 }
 private boolean writeDraft(String data,long generation) {
  synchronized(DRAFT_LOCK){
   if(generation!=draftGeneration)return false;
   android.util.AtomicFile file=draftFile();FileOutputStream stream=null;
   try{stream=file.startWrite();stream.write(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));file.finishWrite(stream);return true;}
   catch(Exception e){if(stream!=null)file.failWrite(stream);return false;}
  }
 }
 private void persistDraft() {
  if(worker.isShutdown())return;String data=snapshotData();if(data==null)return;
  final long generation;synchronized(DRAFT_LOCK){generation=++draftGeneration;}
  draftDismissed=false;
  worker.execute(()->{if(!writeDraft(data,generation))runOnUiThread(()->{if(!isDestroyed()&&generation==draftGeneration)subtitle.setText("Локальный черновик записать не удалось · сохраните копию");});});
 }
 private String checkpointEditor() {
  String data=snapshotData();if(data==null)return null;
  final long generation;synchronized(DRAFT_LOCK){generation=++draftGeneration;}
  if(!writeDraft(data,generation)){subtitle.setText("Локальный черновик записать не удалось · сохраните копию");return null;}
  draftDismissed=false;
  try{return new JSONObject(data).getString("recoveryToken");}catch(JSONException e){return null;}
 }
 private void applyEditorSnapshot(String data) {
  try { JSONObject draft=new JSONObject(data);recoveryToken=draft.optString("recoveryToken",UUID.randomUUID().toString());fileName=draft.optString("name","Восстановленный документ");workingFormat=saveFormat=draft.optString("format","txt");plainSettings=metadataSettings(draft.optJSONObject("settings"));originPlainTable=draft.optBoolean("plainTable");String uri=draft.optString("originalUri");originalUri=uri.isEmpty()?null:Uri.parse(uri);dirty=draft.optBoolean("dirty",true);revision=draft.optLong("revision",0);exportRevision=draft.optLong("exportRevision",-1);exportComplete=draft.optBoolean("exportComplete",false);saveFormat=draft.optString("saveFormat",workingFormat);sheetIndex=draft.optInt("sheet",0);
   String m=draft.optString("mode");if(m.equals("sheet")){sheets=draft.optJSONArray("sheets");if(sheets==null||sheets.length()==0){home();return;}sheetIndex=Math.max(0,Math.min(sheets.length()-1,sheetIndex));showSheet();}else if(m.equals("docx"))showDocument(draft.optJSONArray("paragraphs"));else if(m.equals("txt"))showTextEditor(draft.optString("text"));else{home();return;}
   if(document!=null&&!m.equals("sheet")){int length=document.length();document.setSelection(Math.max(0,Math.min(length,draft.optInt("selectionStart",0))),Math.max(0,Math.min(length,draft.optInt("selectionEnd",0))));}
   subtitle.setText(dirty?"Черновик восстановлен · ещё не записан в выбранную папку":"Редактор восстановлен");
  }catch(Exception e){home();}
 }
 private void restoreLifecycleDraft(String expectedToken) {
  try{String snapshot; synchronized(DRAFT_LOCK){try(InputStream in=draftFile().openRead();ByteArrayOutputStream data=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(data.size()+n>MAX_DRAFT_BYTES)throw new IOException("Черновик превышает защитный лимит");data.write(b,0,n);}snapshot=new String(data.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);}} if(expectedToken==null||!expectedToken.equals(new JSONObject(snapshot).optString("recoveryToken")))throw new IOException("Устаревший черновик");applyEditorSnapshot(snapshot);}
  catch(Exception e){home();message("Не удалось автоматически восстановить последние изменения. Доступный локальный черновик можно открыть вручную; он может быть старше последней правки.");}
 }
 @Override public Object onRetainNonConfigurationInstance() {
  draftHandler.removeCallbacks(draftTask);return snapshotData();
 }
 @Override protected void onSaveInstanceState(Bundle out) {
  draftHandler.removeCallbacks(draftTask);
  if(editorSnapshot()!=null){String token=checkpointEditor();out.putString(RECOVERY_KEY,token==null?"unavailable":token);}
  super.onSaveInstanceState(out);
 }
 @Override protected void onPause() {
  if(editorSnapshot()!=null)checkpointEditor();
  super.onPause();
 }
 private void clearDraft() {
  recoveryToken=null;draftDismissed=true;draftHandler.removeCallbacks(draftTask);synchronized(DRAFT_LOCK){draftGeneration++;draftFile().delete();}
 }
 private void restoreDraft() {
  if(loading)return;background(()->{synchronized(DRAFT_LOCK){try(InputStream in=draftFile().openRead();ByteArrayOutputStream data=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(data.size()+n>16*1024*1024)throw new IOException("Черновик превышает защитный лимит");data.write(buffer,0,n);}JSONObject result=new JSONObject(new String(data.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));return put(result,"ok",true);}}},draft->{closeSheetViewer();closePdf();draftDismissed=true;applyEditorSnapshot(draft.toString());if(!draft.optBoolean("dirty",true))dirty=true;});
 }
 private boolean editorFits(JSONArray metadata) {
  if(metadata==null||metadata.length()>256)return false;long count=0;
  for(int i=0;i<metadata.length();i++){JSONObject sh=metadata.optJSONObject(i);if(sh==null)return false;long rows=sh.optLong("rows",1),cols=sh.optLong("cols",1);if(rows>2000||cols>200)return false;count+=rows*cols;if(count>100000)return false;}
  return true;
 }
 private void showNativeSheetViewer(JSONObject opened) {
  viewerChrome(true);mode="sheetview";viewerSession=opened.optString("session");
  Runnable edit=editorFits(opened.optJSONArray("sheets"))?()->new AlertDialog.Builder(this).setTitle("Редактировать упрощённую копию?").setMessage("Оформление, диаграммы и макросы в копии не сохраняются. Для обычного чтения оставайтесь в просмотрщике.").setNegativeButton("Остаться в просмотре",null).setPositiveButton("Редактировать",(d,w)->startWorkingCopy(()->background(()->nativeCall(put(put(obj(),"op","sheet_open"),"path",source.getAbsolutePath())),result->{sheets=result.optJSONArray("sheets");sheetIndex=0;closeSheetViewer();showSheet();}))).show():null;
  sheetViewer=new SheetViewer(this,content,fileName,opened,request->nativeCall(request),()->home(),edit);sheetViewer.start();
  subtitle.setText("Просмотр книги · без лимита редактора 2000×200");
 }
 private void showMemorySheetViewer() {
  try{MemorySheetBackend backend=new MemorySheetBackend(sheets);JSONObject opened=backend.metadata();viewerChrome(true);mode="sheetview";
   Runnable edit=(originPlainTable||editorFits(opened.optJSONArray("sheets")))?()->new AlertDialog.Builder(this).setTitle("Редактировать копию?").setMessage("Это упрощённый редактор значений. Оформление исходного файла не сохраняется. Для далёкой ячейки используйте изменение по адресу.").setNegativeButton("Отмена",null).setPositiveButton("Редактировать",(d,w)->startWorkingCopy(()->{closeSheetViewer();showSheet();})).show():null;
   sheetViewer=new SheetViewer(this,content,fileName,opened,backend,()->home(),edit);if(originPlainTable)sheetViewer.setImportOptions(()->importAgain());sheetViewer.setWarning(importWarning);sheetViewer.start();subtitle.setText(originPlainTable?workingFormat.toUpperCase(Locale.ROOT)+" · "+plainSettings.optString("encoding","UTF-8")+" · разделитель "+plainSettings.optString("delimiter")+" · значения — текст":"Просмотр импортированных значений · без изменения исходника");
  }catch(Exception e){message("Не удалось подготовить просмотр: "+e.getMessage());}
 }
 private void showDocumentViewer(JSONArray paragraphs) {
  closeDocumentViewer();mode="docview";viewerChrome(true);
  Runnable edit=()->new AlertDialog.Builder(this).setTitle("Редактирование упрощённой копии").setMessage("Таблицы, изображения, поля, стили и сложная вёрстка в редакторе и экспорте не сохраняются. Исходник не изменяется. Продолжить?").setNegativeButton("Отмена",null).setPositiveButton("Редактировать",(d,w)->startWorkingCopy(()->{closeDocumentViewer();showDocument(paragraphs);})).show();
  Runnable export=()->new AlertDialog.Builder(this).setTitle("Экспорт упрощённой копии").setMessage("Экспорт через редактор сохраняет только прежнее подмножество текста и форматирования; таблицы и сложная вёрстка будут утрачены в новой копии. Исходник останется прежним.").setNegativeButton("Отмена",null).setPositiveButton("Продолжить",(d,w)->startWorkingCopy(()->{closeDocumentViewer();showDocument(paragraphs);chooseSaveFormat();})).show();
  documentViewer=new DocumentViewer(this,content,structuredDocument,paragraphs,fileName,()->home(),edit,export);documentViewer.start();
 }
 private void showDocxFallback(JSONObject result) {
  if(docxViewer!=null){content.removeView(docxViewer);docxViewer=null;}viewerChrome(true);mode="docview";content.removeAllViews();
  LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(4),0,dp(4),0);bar.setBackgroundColor(0xff202124);
  Button back=button("←",v->home());bar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
  TextView title=text(fileName,15,Color.WHITE);title.setGravity(Gravity.CENTER_VERTICAL);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);bar.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));Button find=button("⌕",v->{});find.setEnabled(false);find.setContentDescription("Поиск недоступен в этом просмотрщике");bar.addView(find,new LinearLayout.LayoutParams(dp(48),dp(48)));Button more=button("⋮",v->{PopupMenu menu=new PopupMenu(this,v);menu.getMenu().add("О DOCX-просмотре");menu.setOnMenuItemClickListener(item->{message("Показан простой текст: расширенный просмотр недоступен или превышен его лимит. Исходный DOCX не изменяется; поиск и редактирование здесь недоступны.");return true;});menu.show();});more.setContentDescription("Меню DOCX");bar.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));content.addView(bar,new LinearLayout.LayoutParams(-1,dp(48)));
  content.addView(text("DOCX · расширенный просмотр недоступен. Показан доступный простой текст; исходник не изменён.",12,Color.DKGRAY));
  String plain=result.optString("plain_text","");if(plain.isEmpty()){JSONArray ps=result.optJSONArray("paragraphs");if(ps!=null){StringBuilder b=new StringBuilder();for(int i=0;i<ps.length();i++){JSONObject p=ps.optJSONObject(i);if(p!=null){if(b.length()>0)b.append('\n');b.append(p.optString("text",""));}}plain=b.toString();}}
  ScrollView scroll=new ScrollView(this);TextView body=text(plain.isEmpty()?"В документе нет доступного текста.":plain,18,INK);body.setTextIsSelectable(true);body.setBackgroundColor(Color.WHITE);body.setPadding(dp(16),dp(16),dp(16),dp(32));scroll.addView(body,new ScrollView.LayoutParams(-1,-2));content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));subtitle.setText("DOCX · простой текст · исходник не изменяется");
 }
 private void showDocxViewer(JSONObject result) {
  if(result.optBoolean("legacyTextOnly")){showDocumentViewer(result.optJSONArray("paragraphs"));return;}
  viewerChrome(true);mode="docview";content.removeAllViews();final boolean[] ruler={false};
  LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(4),0,dp(4),0);bar.setBackgroundColor(0xff202124);
  Button back=button("←",v->home());bar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
  TextView title=text(fileName,15,Color.WHITE);title.setGravity(Gravity.CENTER_VERTICAL);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);bar.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
  Button find=button("⌕",v->{});find.setEnabled(false);find.setContentDescription("Поиск не поддерживается в просмотрщике DOCX");bar.addView(find,new LinearLayout.LayoutParams(dp(48),dp(48)));
  Button menuButton=button("⋮",v->{PopupMenu menu=new PopupMenu(this,v);menu.getMenu().add("Страница");menu.getMenu().add("Текст");menu.getMenu().add(ruler[0]?"Скрыть линейку":"Показать линейку");menu.getMenu().add("По ширине");for(String z:new String[]{"50%","75%","100%","125%","150%","200%"})menu.getMenu().add(z);menu.getMenu().add("Редактировать копию…");menu.getMenu().add("О DOCX-просмотре");menu.setOnMenuItemClickListener(item->{String a=item.getTitle().toString();if(docxViewer==null)return true;if(a.equals("Страница"))docxViewer.setTextMode(false);else if(a.equals("Текст"))docxViewer.setTextMode(true);else if(a.contains("линейку")){ruler[0]=!ruler[0];docxViewer.setRulerVisible(ruler[0]);}else if(a.equals("По ширине"))docxViewer.setZoom(0);else if(a.endsWith("%"))docxViewer.setZoom(Float.parseFloat(a.substring(0,a.length()-1))/100f);else if(a.startsWith("Редактировать"))new AlertDialog.Builder(this).setTitle("Редактировать копию").setMessage("В редактор переносится упрощённый текст. Теряются вёрстка страниц, таблицы, изображения и форматирование; исходный DOCX не изменяется.").setNegativeButton("Остаться",null).setPositiveButton("Создать копию",(d,w)->startWorkingCopy(()->{closeDocumentViewer();showDocument(result.optJSONArray("paragraphs"));})).show();else if(a.startsWith("О DOCX"))message("Просмотр доступен только для чтения. Пагинация приблизительная; поиск, изображения и часть сложной вёрстки не поддерживаются. Редактирование создаёт упрощённую копию; исходник не изменяется.");return true;});menu.show();});menuButton.setContentDescription("Меню DOCX");bar.addView(menuButton,new LinearLayout.LayoutParams(dp(48),dp(48)));content.addView(bar,new LinearLayout.LayoutParams(-1,dp(48)));
  try{docxViewer=new DocxViewer(this,result);content.addView(docxViewer,new LinearLayout.LayoutParams(-1,0,1));}catch(RuntimeException e){docxViewer=null;showDocxFallback(result);return;}
  String warning=result.optString("warning","");subtitle.setText(warning.isEmpty()?"DOCX · просмотр · исходник не изменяется":"DOCX · "+warning);
 }
 private void showSlides(JSONArray slides) {
  viewerChrome(false);mode="slides"; content.removeAllViews();content.addView(button("← На главную",v->home()));
  content.addView(text(fileName,18,INK));content.addView(text("ТОЛЬКО ТЕКСТ СЛАЙДОВ · Без визуальной вёрстки, редактирования и сохранения",13,BLUE));
  ScrollView scroll=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);
  if(slides!=null)for(int i=0;i<slides.length();i++){JSONObject s=slides.optJSONObject(i);if(s==null)continue;list.addView(text("Слайд "+(i+1)+" · "+s.optString("title"),20,INK));TextView body=text(s.optString("text","На этом слайде нет доступного текста."),16,Color.DKGRAY);body.setTextIsSelectable(true);list.addView(body);}
  content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
 }
 private LinearLayout toolbar() {
  LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
  row.addView(button("←",v->confirmDiscard(()->home())));
  TextView title=text(fileName,14,INK); title.setMaxLines(1); title.setEllipsize(TextUtils.TruncateAt.END); row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
  content.addView(row);
  LinearLayout commands=new LinearLayout(this);commands.addView(button("Сохранить копию",v->{saveFormat=workingFormat;prepareSaveOptions();}));commands.addView(button("Экспорт…",v->chooseSaveFormat()));HorizontalScrollView bar=new HorizontalScrollView(this);bar.addView(commands);content.addView(bar);return row;
 }
 private void showDocument(JSONArray paragraphs) {
  viewerChrome(false);mode="docx";content.removeAllViews();toolbar();
  LinearLayout formatting=new LinearLayout(this);
  formatting.addView(button("Жирный",v->style(Typeface.BOLD)));
  formatting.addView(button("Курсив",v->style(Typeface.ITALIC)));
  formatting.addView(button("Обычный",v->{ if(document==null)return; Editable e=document.getText(); int a=Math.min(document.getSelectionStart(),document.getSelectionEnd()),b=Math.max(document.getSelectionStart(),document.getSelectionEnd()); if(a==b){a=0;b=e.length();} for(StyleSpan span:e.getSpans(a,b,StyleSpan.class))e.removeSpan(span);changed(); }));
  content.addView(formatting);
  ScrollView scroll=new ScrollView(this);document=new EditText(this);document.setGravity(Gravity.TOP);document.setTextSize(18);document.setTextColor(INK);document.setBackgroundColor(Color.WHITE);document.setPadding(dp(18),dp(20),dp(18),dp(60));document.setHint("Начните писать…");document.setMinLines(15);document.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
  SpannableStringBuilder sb=new SpannableStringBuilder(); if(paragraphs!=null)for(int i=0;i<paragraphs.length();i++){JSONObject p=paragraphs.optJSONObject(i);if(p==null)continue;if(i>0)sb.append('\n');int start=sb.length();sb.append(p.optString("text"));int style=(p.optBoolean("bold")?Typeface.BOLD:0)|(p.optBoolean("italic")?Typeface.ITALIC:0);if(style!=0&&sb.length()>start)sb.setSpan(new StyleSpan(style),start,sb.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
  document.setText(sb); document.addTextChangedListener(watcher(()->changed()));scroll.addView(document,new ScrollView.LayoutParams(-1,-2));content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  content.addView(text("DOCX · при сохранении жирный и курсив применяются ко всему абзацу · сохраняйте копию",12,Color.DKGRAY));
 }
 private void style(int style) {
  if(document==null)return;int a=Math.min(document.getSelectionStart(),document.getSelectionEnd()),b=Math.max(document.getSelectionStart(),document.getSelectionEnd());if(a<0)return;if(a==b){message("Выделите текст для форматирования.");return;}
  document.getText().setSpan(new StyleSpan(style),a,b,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);changed();
 }
 private TextWatcher watcher(Runnable run) { return new TextWatcher(){ public void beforeTextChanged(CharSequence s,int start,int count,int after){} public void onTextChanged(CharSequence s,int start,int before,int count){run.run();} public void afterTextChanged(Editable e){} }; }
 private JSONArray paragraphs() {
  JSONArray result=new JSONArray(); Editable e=document.getText();String[] lines=e.toString().split("\n",-1);int offset=0;
  for(String line:lines){boolean bold=false,italic=false;for(StyleSpan span:e.getSpans(offset,Math.min(e.length(),offset+line.length()),StyleSpan.class)){bold|=(span.getStyle()&Typeface.BOLD)!=0;italic|=(span.getStyle()&Typeface.ITALIC)!=0;}result.put(put(put(put(obj(),"text",line),"bold",bold),"italic",italic));offset+=line.length()+1;} return result;
 }
 private void showSheet() {
  viewerChrome(false);mode="sheet";content.removeAllViews();toolbar();
  HorizontalScrollView tabs=new HorizontalScrollView(this);LinearLayout tabRow=new LinearLayout(this);tabs.addView(tabRow);
  for(int i=0;i<sheets.length();i++){final int idx=i;JSONObject s=sheets.optJSONObject(i);if(s==null)continue;Button b=button((i==sheetIndex?"• ":"")+s.optString("name","Лист"),v->{sheetIndex=idx;showSheet();});tabRow.addView(b);}
  content.addView(tabs);
  JSONObject sheet=sheets.optJSONObject(sheetIndex); if(sheet==null)return;
  JSONArray data=sheet.optJSONArray("rows");if(data==null){data=new JSONArray();put(sheet,"rows",data);} final JSONArray rows=data;
  int rowCount=Math.min(40,Math.max(20,rows.length()+5)),cols=8;
  for(int i=0;i<rows.length();i++){JSONArray r=rows.optJSONArray(i);if(r!=null)cols=Math.max(cols,r.length());}cols=Math.min(cols,12);
  content.addView(button("Изменить ячейку по адресу…",v->editCellByAddress()));
  content.addView(text((originPlainTable?"Все значения — текст; 00123 и =… сохраняются строками.":"Упрощённая копия: оформление не сохраняется, формулы не вычисляются.")+" Показано до 40 строк и 12 столбцов. Для остальных ячеек — изменение по адресу; принятые данные сохраняются целиком в пределах формата экспорта.",12,Color.DKGRAY));
  ScrollView vertical=new ScrollView(this);HorizontalScrollView horizontal=new HorizontalScrollView(this);LinearLayout grid=new LinearLayout(this);grid.setOrientation(LinearLayout.VERTICAL);horizontal.addView(grid);vertical.addView(horizontal);
  for(int r=-1;r<rowCount;r++) {LinearLayout line=new LinearLayout(this); TextView number=text(r<0?"":String.valueOf(r+1),13,Color.GRAY);number.setWidth(dp(42));line.addView(number);
   for(int c=0;c<cols;c++){if(r<0){TextView h=text(String.valueOf((char)('A'+c)),14,BLUE);h.setGravity(Gravity.CENTER);line.addView(h,new LinearLayout.LayoutParams(dp(130),dp(44)));continue;}
    final int ri=r,ci=c;EditText cell=new EditText(this);cell.setSingleLine(true);cell.setTextSize(14);cell.setTextColor(INK);cell.setBackgroundColor(Color.WHITE);cell.setPadding(dp(8),0,dp(8),0);JSONArray existing=rows.optJSONArray(r);cell.setText(existing==null?"":existing.optString(c,""));cell.setContentDescription(String.valueOf((char)('A'+c))+(r+1));
    cell.addTextChangedListener(watcher(()->{try{while(rows.length()<=ri)rows.put(new JSONArray());JSONArray target=rows.optJSONArray(ri);if(target==null){target=new JSONArray();rows.put(ri,target);}while(target.length()<=ci)target.put("");target.put(ci,cell.getText().toString());changed();}catch(JSONException ex){message("Ошибка ячейки");}}));
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(130),dp(44));p.setMargins(1,1,1,1);line.addView(cell,p);
   }grid.addView(line);
  } content.addView(vertical,new LinearLayout.LayoutParams(-1,0,1));
 }
 private void editCellByAddress() {
  if(loading)return;EditText address=new EditText(this);address.setSingleLine(true);address.setHint("Например, AZ1500");
  new AlertDialog.Builder(this).setTitle("Адрес ячейки текущего листа").setView(address).setNegativeButton("Отмена",null).setPositiveButton("Открыть",(d,w)->{
   String value=address.getText().toString().trim().toUpperCase(Locale.ROOT);java.util.regex.Matcher match=java.util.regex.Pattern.compile("([A-Z]{1,3})([1-9][0-9]{0,5})").matcher(value);if(!match.matches()){message("Нужен адрес вида A1");return;}int column=0;for(char c:match.group(1).toCharArray())column=column*26+c-'A'+1;int row=Integer.parseInt(match.group(2));int maxRows=originPlainTable?20000:2000,maxCols=originPlainTable?512:200;
   if(row>maxRows||column>maxCols){message("Предел редактора: "+maxRows+" строк и "+maxCols+" столбцов. Исходник не изменён.");return;}final int rr=row-1,cc=column-1;JSONObject sheet=sheets.optJSONObject(sheetIndex);if(sheet==null)return;JSONArray rows=sheet.optJSONArray("rows");if(rows==null)return;JSONArray existing=rows.optJSONArray(rr);EditText input=new EditText(this);input.setMinLines(3);input.setGravity(Gravity.TOP);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setText(existing==null?"":existing.optString(cc,""));
   new AlertDialog.Builder(this).setTitle(value+" · изменить в копии").setView(input).setNegativeButton("Отмена",null).setPositiveButton("Применить",(dialog,which)->{try{long total=0;for(int s=0;s<sheets.length();s++){JSONArray all=sheets.getJSONObject(s).getJSONArray("rows");for(int r=0;r<all.length();r++){JSONArray line=all.optJSONArray(r);if(line!=null)total+=line.length();}}total+=Math.max(0,cc+1-(existing==null?0:existing.length()));if(total>(originPlainTable?500000:100000)){message("Изменение превысит лимит ячеек редактора");return;}while(rows.length()<=rr)rows.put(new JSONArray());JSONArray target=rows.optJSONArray(rr);if(target==null){target=new JSONArray();rows.put(rr,target);}while(target.length()<=cc)target.put("");target.put(cc,input.getText().toString());changed();showSheet();subtitle.setText(value+" изменена · сохраните копию");}catch(Exception e){message("Не удалось изменить ячейку: "+e.getMessage());}}).show();
  }).show();
 }
 private void chooseSaveFormat() {
  if(loading)return;
  boolean doc=mode.equals("docx")||mode.equals("txt");
  final String[] formats=doc?new String[]{"docx","odt","txt"}:new String[]{"xlsx","ods","csv","tsv"};
  final String[] labels=doc?new String[]{"DOCX — упрощённый документ","ODT — только текст, без жирного и курсива","TXT — только текст"}:new String[]{"XLSX — упрощённая копия данных","ODS — значения, без пересчёта формул","CSV — только текущий лист","TSV — только текущий лист"};
  new AlertDialog.Builder(this).setTitle("Экспорт в другой формат").setItems(labels,(dialog,which)->{saveFormat=formats[which];prepareSaveOptions();}).show();
 }
 private void prepareSaveOptions() {
  if(loading)return;
  if(Arrays.asList("txt","csv","tsv").contains(saveFormat))PlainFileUi.chooseExport(this,saveFormat,plainSettings,settings->{plainSettings=metadataSettings(settings);save();});
  else save();
 }
 private String mime(String format) {
  switch(format){case "docx":return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";case "odt":return "application/vnd.oasis.opendocument.text";case "xlsx":return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";case "ods":return "application/vnd.oasis.opendocument.spreadsheet";case "csv":return "text/csv";case "tsv":return "text/tab-separated-values";case "txt":return "text/plain";default:return "application/octet-stream";}
 }
 private void save() {
  if(loading)return;if(!mode.equals("docx")&&!mode.equals("sheet")&&!mode.equals("txt"))return;
  final String format=saveFormat;
  pendingMime=mime(format);
  String ext="."+format;pendingName=fileName.replaceFirst("(?i)\\.(docx|odt|xlsx|xls|ods|csv|tsv|txt)$","")+"-копия"+ext;
  boolean doc=mode.equals("docx")||mode.equals("txt");JSONObject request=put(put(obj(),"op",doc?"docx_save":"sheet_save"),doc?"paragraphs":"sheets",doc?paragraphs():sheets);
  if(doc)put(request,"text",document.getText().toString());put(request,"literal_strings",originPlainTable);
  final JSONObject settings=metadataSettings(plainSettings);final int currentSheet=sheetIndex;
  exportRevision=revision;
  exportComplete=format.equals(workingFormat)&&!settings.optBoolean("neutralizeFormulas")&&(!(format.equals("csv")||format.equals("tsv"))||sheets.length()==1);
  savingOperation=true;saveState="Подготовка копии…";persistDraft();
  final String snapshot=request.toString();
  background(()->{File temp=File.createTempFile("export-",ext,getCacheDir());JSONObject req=new JSONObject(snapshot);put(req,"path",temp.getAbsolutePath());JSONObject result;
   if(format.equals("docx")||format.equals("xlsx"))result=nativeCall(req);
   else if(format.equals("txt")){PlainFormats.saveText(temp,req.optString("text"),settings.optString("encoding","UTF-8"),settings.optString("newline","LF"),settings.optBoolean("bom"));result=put(obj(),"ok",true);}
   else if(format.equals("csv")||format.equals("tsv")){PlainFormats.saveDelimited(temp,req.optJSONArray("sheets"),currentSheet,format,settings.optString("encoding","UTF-8"),settings.optString("delimiter",format.equals("tsv")?"tab":","),settings.optString("newline","LF"),settings.optBoolean("bom"),settings.optBoolean("neutralizeFormulas"));result=put(obj(),"ok",true);}
   else{ModernFormats.save(temp,format,req);result=put(obj(),"ok",true);}return put(result,"exportPath",temp.getAbsolutePath());
  },result->{getPreferences(0).edit().putString("exportPath",result.optString("exportPath")).apply();saveState="Копия подготовлена · выберите место сохранения";subtitle.setText(saveState);Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(pendingMime);i.putExtra(Intent.EXTRA_TITLE,pendingName);try{startActivityForResult(i,SAVE);}catch(Exception e){savingOperation=false;saveState="Не удалось открыть диалог сохранения";subtitle.setText(saveState);message(saveState);}});
 }
 @Override protected void onActivityResult(int request,int result,Intent data) {
  super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null){if(request==SAVE){savingOperation=false;saveState="Сохранение отменено · изменения остаются в редакторе";subtitle.setText(saveState);}return;}
  if(request==OPEN)openUri(data.getData());
  if(request==SAVE){Uri target=data.getData();if(sameDocument(originalUri,target)){savingOperation=false;saveState="Выберите новый файл: перезапись исходника отключена";subtitle.setText(saveState);message(saveState);return;}String path=getPreferences(0).getString("exportPath","");savingOperation=true;saveState="Сохранение копии…";background(()->{File f=new File(path);if(!f.isFile())throw new IOException("Временный файл отсутствует. Повторите сохранение.");try(InputStream in=new FileInputStream(f);OutputStream out=getContentResolver().openOutputStream(target,"wt")){if(out==null)throw new IOException("Нет доступа к выбранной папке");byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.flush();}return put(obj(),"ok",true);},res->{savingOperation=false;if(revision==exportRevision&&exportComplete){dirty=false;clearDraft();}saveState=dirty?"Экспорт записан · остаются несохранённые изменения":"Копия записана · исходник не изменён";subtitle.setText(saveState);message(saveState);});}
 }
 private void showPdf() {
  viewerChrome(true);mode="pdf";pdfViewer=new PdfViewer(this,content,source,fileName,()->home());pdfViewer.start();subtitle.setText("PDF · просмотр страниц · исходник не изменяется");
 }
 private void closePdf(){if(pdfViewer!=null){pdfViewer.close();pdfViewer=null;}}
 private void about() {
  new AlertDialog.Builder(this).setTitle("Свободный офис · 0.2.6 alpha").setMessage("Свободное приложение без оплаты, подписки, рекламы и отправки документов на сервер.\n\nНа основе ядер WordCraft, GridCraft и PdfCraft команды ArtCraft (MIT / Apache-2.0). Просмотр PDF использует системный Android PdfRenderer. Мобильный интерфейс самостоятельный.\n\nЭто не полный аналог Microsoft Office: импорт DOCX и таблиц упрощён, PDF пока доступен только для чтения. Всегда сохраняйте копию важных файлов.\n\nИсходный код приложения распространяется по MIT. Уведомления авторов и лицензии включены в приложение и исходники.").setPositiveButton("Закрыть",null).setNeutralButton("Лицензии",(d,w)->licenses()).show();
 }
 private String readUtf8(InputStream in) throws IOException { ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192]; int n; while((n=in.read(b))!=-1)out.write(b,0,n); return new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8); }
 private void appendLicenseAssets(String path,int depth,StringBuilder out) throws IOException {
  if(depth>8)throw new IOException("Слишком глубокая папка лицензий");
  String[] entries=getAssets().list(path);if(entries==null)entries=new String[0];
  if(entries.length>0){Arrays.sort(entries);for(String entry:entries)appendLicenseAssets(path+"/"+entry,depth+1,out);return;}
  if(out.length()>=2_000_000)throw new IOException("Текст лицензий превышает лимит");
  out.append("\n\n--- ").append(path.substring(path.lastIndexOf('/')+1)).append(" ---\n");
  try(InputStream in=getAssets().open(path);ByteArrayOutputStream data=new ByteArrayOutputStream()){
   byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(data.size()+n>2_000_000-out.length())throw new IOException("Текст лицензий превышает лимит 2 МБ");data.write(b,0,n);}
   out.append(new String(data.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
  }
 }
 private void licenses(){try{StringBuilder s=new StringBuilder();appendLicenseAssets("licenses",0,s);TextView t=text(s.toString(),12,INK);t.setTextIsSelectable(true);ScrollView v=new ScrollView(this);v.setPadding(dp(16),0,dp(16),0);v.addView(t);new AlertDialog.Builder(this).setTitle("Открытые лицензии").setView(v).setPositiveButton("Закрыть",null).show();}catch(IOException e){message("Не удалось открыть полный список лицензий. Файлы доступны в архиве исходного кода.");}}
 @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getData()!=null)confirmDiscard(()->openUri(intent.getData()));}
 @Override public void onBackPressed(){if(mode.equals("home"))super.onBackPressed();else confirmDiscard(()->home());}
 @Override protected void onStop(){draftHandler.removeCallbacks(draftTask);super.onStop();}
 @Override protected void onDestroy(){draftHandler.removeCallbacks(draftTask);closeSheetViewer();closePdf();closeDocumentViewer();worker.shutdown();super.onDestroy();}
}
