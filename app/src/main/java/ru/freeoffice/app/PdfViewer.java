package ru.freeoffice.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only continuous PDF viewer. All PdfRenderer calls run on one worker. */
public final class PdfViewer {
    private static final int MAX_BITMAP_SIDE=4096, MAX_JOB_PIXELS=2_000_000;
    private static final int MAX_CACHE_PIXELS=4_000_000, MAX_CACHE_PAGES=3;
    private static final int MAX_HITS=2000, MAX_HIT_RECTS=20000, GAP_DP=12;
    private final Activity activity; private final LinearLayout host; private final File file; private final String name; private final Runnable back;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final LinkedHashMap<RenderKey,Bitmap> cache=new LinkedHashMap<>(4,.75f,true);
    private int cachePixels; private volatile boolean closed;
    private ParcelFileDescriptor descriptor; private PdfRenderer renderer;
    private ViewerScroll scroll; private PdfCanvas canvas; private TextView status,pageLabel,zoomLabel,searchExplanation; private EditText searchInput; private Button searchButton;
    private int[] widths,heights,tops; private boolean[] known,dimensionQueued,renderFailed;
    private int pageCount,pageIndex; private float zoom=1f,panX; private Mode mode=Mode.WIDTH;
    private volatile long generation,searchToken;
    private boolean started,opened,searchSawText,searchTruncated,searching,workerBusy;
    private int searchHit,totalHits,totalHitRects,pendingHit=-1,pendingHitPage=-1; private long pendingHitToken; private String pendingHitQuery; private final List<Hit> hits=new ArrayList<>();
    private final ScaleGestureDetector scaleDetector;
    private enum Mode { WIDTH,PAGE,EXPLICIT }
    private static final class RenderKey {
        final int page,width,height; final long generation;
        RenderKey(int p,int w,int h,long g){page=p;width=w;height=h;generation=g;}
        @Override public int hashCode(){return (((page*31)+width)*31+height)*31+(int)generation;}
        @Override public boolean equals(Object o){if(!(o instanceof RenderKey))return false;RenderKey k=(RenderKey)o;return page==k.page&&width==k.width&&height==k.height&&generation==k.generation;}
    }
    private static final class Hit {final int page;final List<RectF> rects;Hit(int p,List<RectF> r){page=p;rects=r;}}
    public PdfViewer(Activity a,LinearLayout h,File f,String n,Runnable b){
        activity=a;host=h;file=f;name=n;back=b;
        scaleDetector=new ScaleGestureDetector(a,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){if(!opened)return false;applyZoom(visualScale()*d.getScaleFactor(),d.getFocusX(),d.getFocusY());return true;}
        });
    }
    private int dp(float v){return Math.round(v*activity.getResources().getDisplayMetrics().density);}
    private Button button(String s,Runnable r){Button b=new Button(activity);b.setText(s);b.setAllCaps(false);b.setOnClickListener(v->r.run());return b;}
    public void start(){
        if(started||closed)return;started=true;host.removeAllViews();LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);host.addView(root,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout head=new LinearLayout(activity);head.setGravity(Gravity.CENTER_VERTICAL);head.setBackgroundColor(0xff202124);root.addView(head,new LinearLayout.LayoutParams(-1,dp(48)));
        Button backButton=button("‹",back);backButton.setContentDescription("Назад");backButton.setTextColor(Color.WHITE);head.addView(backButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView title=new TextView(activity);title.setText(name);title.setTextColor(Color.WHITE);title.setGravity(Gravity.CENTER_VERTICAL);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);head.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        Button findToggle=button("⌕",()->{View panel=root.findViewWithTag("pdf_search_panel");if(panel!=null)panel.setVisibility(panel.getVisibility()==View.GONE?View.VISIBLE:View.GONE);});findToggle.setTextColor(Color.WHITE);findToggle.setEnabled(PdfTextSearch.isSupported());findToggle.setContentDescription(PdfTextSearch.isSupported()?"Найти":"Поиск требует Android 15 / API 35");head.addView(findToggle,new LinearLayout.LayoutParams(dp(48),dp(48)));
        Button menu=button("⋮",()->showViewerMenu());menu.setTextColor(Color.WHITE);menu.setTag("pdf_menu_anchor");menu.setContentDescription("Дополнительные действия");head.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout searchPanel=new LinearLayout(activity);searchPanel.setOrientation(LinearLayout.VERTICAL);searchPanel.setTag("pdf_search_panel");searchPanel.setVisibility(View.GONE);root.addView(searchPanel,new LinearLayout.LayoutParams(-1,-2));LinearLayout find=new LinearLayout(activity);find.setGravity(Gravity.CENTER_VERTICAL);searchPanel.addView(find,new LinearLayout.LayoutParams(-1,-2));
        searchInput=new EditText(activity);searchInput.setSingleLine(true);searchInput.setHint("Поиск в тексте PDF");searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);find.addView(searchInput,new LinearLayout.LayoutParams(0,dp(48),1));searchButton=button("Найти",this::startSearch);find.addView(searchButton,new LinearLayout.LayoutParams(-2,dp(48)));find.addView(button("‹",()->nextHit(-1)),new LinearLayout.LayoutParams(dp(48),dp(48)));find.addView(button("›",()->nextHit(1)),new LinearLayout.LayoutParams(dp(48),dp(48)));
        searchInput.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){invalidateSearch();}public void afterTextChanged(Editable e){}});
        searchButton.setEnabled(PdfTextSearch.isSupported());searchExplanation=new TextView(activity);searchExplanation.setTextSize(12);if(!PdfTextSearch.isSupported())searchExplanation.setText("Поиск текста требует Android 15 (API 35) или новее.");searchPanel.addView(searchExplanation,new LinearLayout.LayoutParams(-1,-2));
        scroll=new ViewerScroll(activity);scroll.setFillViewport(false);canvas=new PdfCanvas();scroll.addView(canvas,new ScrollView.LayoutParams(-1,1));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout nav=new LinearLayout(activity);nav.setGravity(Gravity.CENTER_VERTICAL);root.addView(nav,new LinearLayout.LayoutParams(-1,dp(48)));nav.addView(button("‹",()->go(pageIndex-1)),new LinearLayout.LayoutParams(dp(48),dp(48)));pageLabel=new TextView(activity);pageLabel.setGravity(Gravity.CENTER);nav.addView(pageLabel,new LinearLayout.LayoutParams(0,-1,1));nav.addView(button("Перейти",this::gotoPage),new LinearLayout.LayoutParams(-2,dp(48)));nav.addView(button("›",()->go(pageIndex+1)),new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout footer=new LinearLayout(activity);footer.setGravity(Gravity.CENTER_VERTICAL);root.addView(footer,new LinearLayout.LayoutParams(-1,dp(32)));status=new TextView(activity);status.setTextSize(12);status.setTextColor(Color.DKGRAY);footer.addView(status,new LinearLayout.LayoutParams(0,-1,1));zoomLabel=new TextView(activity);zoomLabel.setTextSize(12);footer.addView(zoomLabel,new LinearLayout.LayoutParams(-2,-1));
        labels();scroll.setOnScrollChangeListener((View v,int x,int y,int ox,int oy)->onViewportChanged());
        worker.execute(()->{Exception error=null;int count=0;try{descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);renderer=new PdfRenderer(descriptor);count=renderer.getPageCount();if(count<1)throw new IllegalStateException("В PDF нет страниц");}catch(Exception e){error=e;}catch(OutOfMemoryError e){error=new Exception("Недостаточно памяти для PDF",e);}if(error!=null)release();final Exception result=error;final int pages=count;activity.runOnUiThread(()->{if(closed)return;if(result!=null){fail(result);return;}pageCount=pages;widths=new int[pages];heights=new int[pages];tops=new int[pages];known=new boolean[pages];dimensionQueued=new boolean[pages];renderFailed=new boolean[pages];opened=true;canvas.updateExtent();labels();status.setText("Прокрутка документа…");requestVisibleWork();});});
    }
    private void showViewerMenu(){
        if(closed)return;View anchor=host.findViewWithTag("pdf_menu_anchor");if(anchor==null)return;
        String[] items={"По ширине","Вся страница","100%","Уменьшить","Увеличить","Перейти к странице",PdfTextSearch.isSupported()?"Найти":"Найти (требуется Android 15 / API 35)","Следующее совпадение","Предыдущее совпадение","О программе и ограничениях"};
        android.widget.PopupMenu popup=new android.widget.PopupMenu(activity,anchor);
        for(int i=0;i<items.length;i++){android.view.MenuItem item=popup.getMenu().add(0,i,i,items[i]);item.setEnabled(i==9||(opened&&(i==6?PdfTextSearch.isSupported()&&searchButton!=null&&searchButton.isEnabled():i==7||i==8?!hits.isEmpty():true)));}
        popup.setOnMenuItemClickListener(item->{if(closed)return true;switch(item.getItemId()){
            case 0:setMode(Mode.WIDTH);break;case 1:setMode(Mode.PAGE);break;case 2:setExplicitZoom(1f);break;
            case 3:setExplicitZoom(visualScale()/1.25f);break;case 4:setExplicitZoom(visualScale()*1.25f);break;case 5:gotoPage();break;
            case 6:View panel=host.findViewWithTag("pdf_search_panel");if(panel!=null)panel.setVisibility(View.VISIBLE);searchInput.requestFocus();break;
            case 7:nextHit(1);break;case 8:nextHit(-1);break;
            case 9:new AlertDialog.Builder(activity).setTitle("О PDF-просмотре").setMessage("Только чтение. Поиск и подсветка требуют Android 15 (API 35) и текстового слоя; OCR и ввод пароля недоступны. Ограничены растр (2 млн пикселей), кэш (4 млн пикселей / 3 страницы) и число результатов. Точность подсветки и жестов требует проверки на устройстве.").setPositiveButton("ОК",null).show();break;
        }return true;});popup.show();
    }
    private float visualScale(){return mode==Mode.EXPLICIT?zoom:currentScale();}
    private float currentScale(){if(!opened||scroll==null||pageCount==0||widths==null||!known[pageIndex])return zoom;float d=activity.getResources().getDisplayMetrics().density;float w=Math.max(1,scroll.getWidth()-dp(16))/(widths[pageIndex]*d);return mode==Mode.PAGE?Math.min(w,Math.max(1,scroll.getHeight())/(heights[pageIndex]*d)):w;}
    private float pageScale(int p){float d=activity.getResources().getDisplayMetrics().density;float w=Math.max(1,scroll.getWidth()-dp(16))/(Math.max(1,widths[p])*d);if(mode==Mode.WIDTH)return w;if(mode==Mode.PAGE)return Math.min(w,Math.max(1,scroll.getHeight())/(Math.max(1,heights[p])*d));return zoom;}
    private int displayedHeight(int p){if(!known[p])return dp(180);return Math.max(dp(80),(int)(heights[p]*activity.getResources().getDisplayMetrics().density*pageScale(p)))+dp(8);}
    private void setExplicitZoom(float z){if(!opened)return;applyZoom(Math.max(.25f,Math.min(5f,z)),scroll.getWidth()/2f,scroll.getHeight()/2f);}
    private void setMode(Mode m){if(!opened)return;panX=0;applyMode(m);}
    private void applyMode(Mode m){float focus=scroll.getHeight()/2f;float doc=scroll.getScrollY()+focus;int p=pageAtY((int)doc);float fraction=p<0?0:(doc-tops[p])/(float)Math.max(1,displayedHeight(p));mode=m;if(m==Mode.EXPLICIT)zoom=visualScale();geometryChanged();if(p>=0)scroll.post(()->scroll.scrollTo(0,Math.max(0,tops[p]+Math.round(fraction*displayedHeight(p))-Math.round(focus))));labels();}
    private void applyZoom(float newScale,float focusX,float focusY){float screenFocus=focusY;float doc=scroll.getScrollY()+focusY;int p=pageAtY((int)doc);float fraction=p<0?0:(doc-tops[p])/(float)Math.max(1,displayedHeight(p));float pagePoint=0;if(p>=0&&known[p]){float oldScale=pageScale(p)*activity.getResources().getDisplayMetrics().density;float oldLeft=Math.max(dp(8),(scroll.getWidth()-widths[p]*oldScale)/2f)-panX;pagePoint=(focusX-oldLeft)/Math.max(1,widths[p]*oldScale);}mode=Mode.EXPLICIT;zoom=Math.max(.25f,Math.min(5f,newScale));panX=0;geometryChanged();if(p>=0){if(known[p]){float newWidth=widths[p]*pageScale(p)*activity.getResources().getDisplayMetrics().density;float baseLeft=Math.max(dp(8),(scroll.getWidth()-newWidth)/2f);panX=Math.max(0,Math.min(Math.max(0,newWidth-scroll.getWidth()+dp(16)),baseLeft+pagePoint*newWidth-focusX));}scroll.post(()->scroll.scrollTo(0,Math.max(0,tops[p]+Math.round(fraction*displayedHeight(p))-Math.round(screenFocus))));}labels();}
    private void geometryChanged(){generation++;recycleCache();if(canvas!=null)canvas.updateExtent();if(canvas!=null)canvas.invalidate();requestVisibleWork();}
    private void recycleCache(){for(Bitmap b:cache.values())recycle(b);cache.clear();cachePixels=0;}
    private void onViewportChanged(){if(!opened)return;int p=pageAtY(scroll.getScrollY()+scroll.getHeight()/3);if(p>=0&&p!=pageIndex){pageIndex=p;labels();}requestVisibleWork();}
    private int pageAtY(int y){if(pageCount==0||tops==null)return-1;for(int i=0;i<pageCount;i++)if(y<tops[i]+displayedHeight(i)+dp(GAP_DP))return i;return pageCount-1;}
    private void requestVisibleWork(){
        if(!opened||closed||workerBusy||scroll==null||scroll.getWidth()<=0||scroll.getHeight()<=0)return;
        int top=scroll.getScrollY(),bottom=top+scroll.getHeight();
        List<Integer> visible=new ArrayList<>();for(int i=0;i<pageCount;i++)if(tops[i]+displayedHeight(i)>=top&&tops[i]<=bottom)visible.add(i);
        final float center=(top+bottom)/2f;visible.sort((a,b)->{float da=Math.abs(tops[a]+displayedHeight(a)/2f-center),db=Math.abs(tops[b]+displayedHeight(b)/2f-center);int c=Float.compare(da,db);return c!=0?c:Integer.compare(a,b);});
        List<Integer> wanted=new ArrayList<>();long budget=MAX_CACHE_PIXELS;int slots=MAX_CACHE_PAGES;
        for(int p:visible){if(!known[p]){if(!renderFailed[p]){enqueueDimensions(p);return;}continue;}if(renderFailed[p])continue;long pixels=renderPixels(p);if(slots>0&&pixels<=budget){wanted.add(p);slots--;budget-=pixels;}}
        for(java.util.Iterator<Map.Entry<RenderKey,Bitmap>> it=cache.entrySet().iterator();it.hasNext();){Map.Entry<RenderKey,Bitmap> e=it.next();if(!wanted.contains(e.getKey().page)||e.getKey().generation!=generation){cachePixels-=e.getValue().getWidth()*e.getValue().getHeight();recycle(e.getValue());it.remove();}}
        for(int p:wanted)if(!hasBitmap(p)){enqueueRender(p);return;}
    }
    private long renderPixels(int p){int dw=Math.max(1,(int)(widths[p]*activity.getResources().getDisplayMetrics().density*pageScale(p))),dh=Math.max(1,(int)(heights[p]*activity.getResources().getDisplayMetrics().density*pageScale(p)));float cap=Math.min(1f,Math.min((float)MAX_BITMAP_SIDE/dw,Math.min((float)MAX_BITMAP_SIDE/dh,(float)Math.sqrt((double)MAX_JOB_PIXELS/((double)dw*dh)))));return (long)Math.max(1,(int)(dw*cap))*Math.max(1,(int)(dh*cap));}
    private boolean hasBitmap(int p){for(RenderKey k:cache.keySet())if(k.page==p&&k.generation==generation)return true;return false;}
    private void enqueueDimensions(int p){if(dimensionQueued[p]||workerBusy)return;dimensionQueued[p]=true;workerBusy=true;long gen=generation;worker.execute(()->{int w=0,h=0;Exception err=null;try(PdfRenderer.Page page=renderer.openPage(p)){w=page.getWidth();h=page.getHeight();if(w<=0||h<=0)throw new IllegalStateException("Некорректный размер страницы");}catch(Exception e){err=e;}catch(OutOfMemoryError e){err=new Exception("Недостаточно памяти для размеров страницы",e);}final int fw=w,fh=h;final Exception error=err;activity.runOnUiThread(()->{workerBusy=false;if(closed)return;dimensionQueued[p]=false;if(gen==generation&&error==null){int anchor=pageAtY(scroll.getScrollY());float fraction=anchor<0?0:(scroll.getScrollY()-tops[anchor])/(float)Math.max(1,displayedHeight(anchor));widths[p]=fw;heights[p]=fh;known[p]=true;canvas.updateExtent();if(anchor>=0)scroll.scrollTo(0,Math.max(0,tops[anchor]+Math.round(fraction*displayedHeight(anchor))));if(pendingHit>=0&&pendingHit==searchHit&&pendingHitPage==p&&pendingHitToken==searchToken&&pendingHitQuery!=null&&pendingHitQuery.equals(searchInput.getText().toString().trim())){int hit=pendingHit;pendingHit=-1;pendingHitPage=-1;pendingHitQuery=null;navigateHit(hit);}}else if(error!=null){renderFailed[p]=true;pageError(p,error);}requestVisibleWork();});});}
    private void enqueueRender(int p){int dw=Math.max(1,(int)(widths[p]*activity.getResources().getDisplayMetrics().density*pageScale(p))),dh=Math.max(1,(int)(heights[p]*activity.getResources().getDisplayMetrics().density*pageScale(p)));float cap=Math.min(1f,Math.min((float)MAX_BITMAP_SIDE/dw,Math.min((float)MAX_BITMAP_SIDE/dh,(float)Math.sqrt((double)MAX_JOB_PIXELS/((double)dw*dh)))));int bw=Math.max(1,(int)(dw*cap)),bh=Math.max(1,(int)(dh*cap));if(cap<1f&&!searching)status.setText("Страница отображается с ограничением разрешения для защиты памяти.");if((long)bw*bh>MAX_CACHE_PIXELS)return;RenderKey key=new RenderKey(p,bw,bh,generation);workerBusy=true;long gen=generation;worker.execute(()->{Bitmap bitmap=null;Exception err=null;try(PdfRenderer.Page page=renderer.openPage(p)){bitmap=Bitmap.createBitmap(bw,bh,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);}catch(Exception e){err=e;}catch(OutOfMemoryError e){err=new Exception("Недостаточно памяти для страницы",e);}final Bitmap result=bitmap;final Exception error=err;activity.runOnUiThread(()->{workerBusy=false;if(closed||gen!=generation){recycle(result);requestVisibleWork();return;}if(error!=null){renderFailed[p]=true;recycle(result);pageError(p,error);}else{putCache(key,result);canvas.invalidate();}requestVisibleWork();});});}
    private void putCache(RenderKey key,Bitmap bitmap){if(bitmap==null)return;while(!cache.isEmpty()&&(cache.size()>=MAX_CACHE_PAGES||cachePixels+(long)bitmap.getWidth()*bitmap.getHeight()>MAX_CACHE_PIXELS)){Map.Entry<RenderKey,Bitmap> e=cache.entrySet().iterator().next();cache.remove(e.getKey());cachePixels-=e.getValue().getWidth()*e.getValue().getHeight();recycle(e.getValue());}cache.put(key,bitmap);cachePixels+=bitmap.getWidth()*bitmap.getHeight();}
    private static void recycle(Bitmap b){if(b!=null&&!b.isRecycled())b.recycle();}
    private void go(int p){if(!opened||p<0||p>=pageCount)return;pageIndex=p;labels();if(!known[p]&&!renderFailed[p])requestVisibleWork();scroll.post(()->scroll.smoothScrollTo(0,tops[p]));}
    private void gotoPage(){if(!opened)return;EditText input=new EditText(activity);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setHint("1–"+pageCount);new AlertDialog.Builder(activity).setTitle("Перейти к странице").setView(input).setNegativeButton("Отмена",null).setPositiveButton("Открыть",(d,w)->{try{int p=Integer.parseInt(input.getText().toString());if(p<1||p>pageCount)throw new NumberFormatException();go(p-1);}catch(NumberFormatException e){status.setText("Нужен номер от 1 до "+pageCount);}}).show();}
    private void labels(){if(pageLabel!=null)pageLabel.setText((pageCount==0?0:pageIndex+1)+" / "+pageCount);if(zoomLabel!=null)zoomLabel.setText(Math.round((!opened?zoom:visualScale())*100)+"%");}
    private void pageError(int p,Exception e){if(p==pageIndex&&status!=null)status.setText("Страница "+(p+1)+": "+safeMessage(e));}
    private void invalidateSearch(){searchToken++;pendingHit=-1;pendingHitPage=-1;pendingHitQuery=null;hits.clear();totalHits=totalHitRects=searchHit=0;searchTruncated=false;searching=false;if(status!=null)status.setText("");if(searchExplanation!=null&&PdfTextSearch.isSupported()&&(searchButton==null||searchButton.isEnabled()))searchExplanation.setText("");if(canvas!=null)canvas.invalidate();}
    private void startSearch(){if(!opened)return;if(!PdfTextSearch.isSupported()){status.setText("Поиск текста поддерживается только на Android 15 (API 35) и новее.");return;}String q=searchInput.getText().toString().trim();if(q.isEmpty()){status.setText("Введите текст для поиска");return;}invalidateSearch();long token=++searchToken;searching=true;searchSawText=false;searchTruncated=false;status.setText("Поиск…");searchNextPage(0,token,q);}
    private void searchNextPage(int p,long token,String q){if(closed||token!=searchToken)return;if(p>=pageCount){searching=false;if(totalHits==0){if(!searchSawText){searchButton.setEnabled(false);searchExplanation.setText("В документе не обнаружен доступный текстовый слой; OCR не выполняется.");}status.setText(searchSawText?"Совпадений нет":"В документе не обнаружен доступный текстовый слой");}else{status.setText("Совпадений: "+totalHits+(searchTruncated?"; показана часть результатов":""));navigateHit(0);}return;}if(p==0)requestVisibleWork();worker.execute(()->{PdfTextSearch.Result r=null;Exception err=null;try(PdfRenderer.Page page=renderer.openPage(p)){r=PdfTextSearch.searchPage(page,q);}catch(Exception e){err=e;}catch(OutOfMemoryError e){err=new Exception("Недостаточно памяти; поиск остановлен, показаны частичные результаты",e);}final PdfTextSearch.Result result=r;final Exception error=err;activity.runOnUiThread(()->{if(closed||token!=searchToken)return;if(error!=null){searching=false;searchTruncated=true;status.setText("Поиск остановлен на странице "+(p+1)+": "+safeMessage(error)+"; частичные результаты");return;}searchSawText|=result.hasText;searchTruncated|=result.truncated;for(List<RectF> match:result.matches){if(hits.size()>=MAX_HITS){searchTruncated=true;break;}List<RectF> copy=new ArrayList<>();for(RectF rect:match){if(totalHitRects>=MAX_HIT_RECTS){searchTruncated=true;break;}copy.add(new RectF(rect));totalHitRects++;}if(!copy.isEmpty()){hits.add(new Hit(p,copy));totalHits++;}else if(searchTruncated)break;}canvas.invalidate();status.setText("Поиск… "+(p+1)+" / "+pageCount);if(searchTruncated&&(totalHits>=MAX_HITS||totalHitRects>=MAX_HIT_RECTS)){searching=false;status.setText("Показана часть результатов (достигнут безопасный лимит)");return;}requestVisibleWork();searchNextPage(p+1,token,q);});});}
    private void nextHit(int d){if(hits.isEmpty())return;navigateHit((searchHit+d+hits.size())%hits.size());}
    private void navigateHit(int index){if(index<0||index>=hits.size())return;searchHit=index;Hit hit=hits.get(index);go(hit.page);status.setText("Совпадение "+(index+1)+" / "+totalHits+(searchTruncated?" · частичные результаты":""));if(!hit.rects.isEmpty()&&known[hit.page]){pendingHit=-1;pendingHitPage=-1;pendingHitQuery=null;RectF r=hit.rects.get(0);float scale=pageScale(hit.page)*activity.getResources().getDisplayMetrics().density;int target=tops[hit.page]+dp(4)+(int)(r.top*scale);float pageW=widths[hit.page]*scale;float baseLeft=Math.max(dp(8),(scroll.getWidth()-pageW)/2f);int viewportWidth=scroll.getWidth();float maxPan=Math.max(0,pageW-viewportWidth+dp(16));panX=Math.max(0,Math.min(maxPan,baseLeft+r.centerX()*scale-viewportWidth/2f));scroll.post(()->scroll.scrollTo(0,Math.max(0,target-scroll.getHeight()/3)));}else if(!hit.rects.isEmpty()){pendingHit=index;pendingHitPage=hit.page;pendingHitToken=searchToken;pendingHitQuery=searchInput.getText().toString().trim();scroll.post(()->scroll.scrollTo(0,tops[hit.page]));}canvas.invalidate();}
    private static String safeMessage(Exception e){String m=e.getMessage();if(e instanceof SecurityException||(m!=null&&(m.toLowerCase().contains("password")||m.toLowerCase().contains("encrypt"))))return "PDF защищён паролем или доступ запрещён; такие файлы не поддерживаются";return m==null?"ошибка чтения":m;}
    private void fail(Exception e){if(status!=null)status.setText("Не удалось показать PDF: "+safeMessage(e));}
    private void release(){try{if(renderer!=null)renderer.close();}catch(Exception ignored){}renderer=null;try{if(descriptor!=null)descriptor.close();}catch(Exception ignored){}descriptor=null;}
    public void close(){if(closed)return;closed=true;generation++;searchToken++;recycleCache();worker.execute(this::release);worker.shutdown();}
    private final class ViewerScroll extends ScrollView {
        float lastX,lastY;boolean pinch,pan;
        ViewerScroll(Activity a){super(a);setOnTouchListener((v,e)->{int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN){lastX=e.getX();lastY=e.getY();pinch=false;pan=false;}if(action==MotionEvent.ACTION_POINTER_DOWN){pinch=true;getParent().requestDisallowInterceptTouchEvent(true);}scaleDetector.onTouchEvent(e);if(e.getPointerCount()>1||scaleDetector.isInProgress()){pinch=true;getParent().requestDisallowInterceptTouchEvent(true);return true;}if(action==MotionEvent.ACTION_MOVE&&mode==Mode.EXPLICIT&&!pinch){float dx=e.getX()-lastX,dy=e.getY()-lastY;if(!pan&&Math.abs(dx)>dp(6)&&Math.abs(dx)>Math.abs(dy)){pan=true;getParent().requestDisallowInterceptTouchEvent(true);}if(pan){float renderedWidth=known[pageIndex]?widths[pageIndex]*activity.getResources().getDisplayMetrics().density*pageScale(pageIndex):getWidth();panX=Math.max(0,Math.min(Math.max(0,renderedWidth-getWidth()+dp(16)),panX-dx));canvas.invalidate();scrollTo(0,getScrollY());lastX=e.getX();return true;}}if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){getParent().requestDisallowInterceptTouchEvent(false);if(pinch)pinch=false;if(pan)return true;}lastX=e.getX();lastY=e.getY();return false;});}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);if(opened&&(w!=ow||h!=oh))geometryChanged();}
        @Override public boolean onInterceptTouchEvent(MotionEvent e){if(e.getPointerCount()>1){getParent().requestDisallowInterceptTouchEvent(true);return true;}return super.onInterceptTouchEvent(e);}
    }
    private final class PdfCanvas extends View {
        final Paint paper=new Paint(Paint.ANTI_ALIAS_FLAG),bitmapPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG),highlight=new Paint(Paint.ANTI_ALIAS_FLAG),selectedHighlight=new Paint(Paint.ANTI_ALIAS_FLAG);
        PdfCanvas(){super(activity);paper.setColor(Color.WHITE);highlight.setColor(0x66ffca28);selectedHighlight.setColor(0x993f51b5);}
        void updateExtent(){if(!opened||scroll==null)return;long y=dp(8);for(int i=0;i<pageCount;i++){tops[i]=(int)Math.min(Integer.MAX_VALUE/2,y);y+=displayedHeight(i)+dp(GAP_DP);if(y>Integer.MAX_VALUE/2)y=Integer.MAX_VALUE/2;}ViewGroup.LayoutParams lp=getLayoutParams();if(lp!=null){lp.height=(int)Math.max(dp(1),Math.min(Integer.MAX_VALUE/2,y));lp.width=-1;setLayoutParams(lp);}setMinimumWidth(0);requestLayout();}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);if(opened)requestVisibleWork();}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            if(!opened)return;
            Rect clip=new Rect();
            c.getClipBounds(clip);
            for(int p=0;p<pageCount;p++){
                int top=tops[p],height=displayedHeight(p);
                if(top+height<clip.top||top>clip.bottom)continue;
                float width=known[p]?widths[p]*activity.getResources().getDisplayMetrics().density*pageScale(p):Math.max(1,scroll.getWidth()-dp(16));
                float left=Math.max(dp(8),(scroll.getWidth()-width)/2f)-panX;
                float ph=known[p]?heights[p]*activity.getResources().getDisplayMetrics().density*pageScale(p):height-dp(8);
                RectF dst=new RectF(left,top+dp(4),left+width,top+dp(4)+ph);
                c.drawRect(dst,paper);
                Bitmap image=findBitmap(p);
                if(image!=null&&!image.isRecycled())c.drawBitmap(image,null,dst,bitmapPaint);
                drawHits(c,p,dst);
            }
        }
        Bitmap findBitmap(int p){for(Map.Entry<RenderKey,Bitmap> e:cache.entrySet())if(e.getKey().page==p&&e.getKey().generation==generation)return e.getValue();return null;}
        void drawHits(Canvas c,int p,RectF dst){if(!known[p]||widths[p]<=0||heights[p]<=0)return;float sx=dst.width()/widths[p],sy=dst.height()/heights[p];for(int i=0;i<hits.size();i++)if(hits.get(i).page==p)for(RectF r:hits.get(i).rects){RectF box=new RectF(dst.left+r.left*sx,dst.top+r.top*sy,dst.left+r.right*sx,dst.top+r.bottom*sy);c.drawRect(box,i==searchHit?selectedHighlight:highlight);}}
    }
}
