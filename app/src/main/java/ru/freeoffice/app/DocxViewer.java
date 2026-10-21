package ru.freeoffice.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.Layout;
import android.graphics.text.LineBreaker;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.text.style.LeadingMarginSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

/** Bounded read-only renderer for native docx_view JSON. */
public final class DocxViewer extends FrameLayout {
    private static final float PT=96f/72f;
    private static final int MAX_BLOCKS=4000, MAX_RUNS=30000, MAX_CHARS=1_500_000, MAX_ROWS=3000, MAX_CELLS=12000, MAX_DEPTH=16;
    private final JSONObject root;
    private final ArrayList<Block> blocks=new ArrayList<>();
    private final ArrayList<Section> sections=new ArrayList<>();
    private final ScrollView scroll;
    private final FrameLayout inner;
    private final PageCanvas canvas;
    private boolean textMode, rulerVisible;
    private float zoom;

    public DocxViewer(Context context, JSONObject document) {
        super(context); root=document==null?new JSONObject():document; setBackgroundColor(Color.rgb(232,235,241)); parse(root);
        scroll=new ScrollView(context);scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(true);
        inner=new FrameLayout(context);canvas=new PageCanvas(context);inner.addView(canvas,new FrameLayout.LayoutParams(-1,-1));scroll.addView(inner,new ScrollView.LayoutParams(-1,-2));addView(scroll,new FrameLayout.LayoutParams(-1,-1));TextView note=new TextView(context);note.setText("Просмотр: пагинация по блокам; точный интервал не поддерживается. Вложенные таблицы и сложное форматирование могут отображаться упрощённо.");note.setTextSize(11);note.setTextColor(Color.DKGRAY);note.setBackgroundColor(Color.rgb(245,246,248));note.setPadding(8,4,8,4);addView(note,new FrameLayout.LayoutParams(-1,-2,android.view.Gravity.BOTTOM));
    }
    public void setTextMode(boolean value){if(textMode==value)return;textMode=value;rebuildMode();}
    public void setRulerVisible(boolean value){rulerVisible=value;canvas.invalidate();}
    public void setZoom(float factor){zoom=factor<=0?0:Math.max(.5f,Math.min(2f,factor));canvas.requestLayout();}
    private static float num(JSONObject o,String k,float d){return o==null?d:(float)o.optDouble(k,d);}
    private static String str(JSONObject o,String k,String d){return o==null?d:o.optString(k,d);}
    private void parse(JSONObject r){
        JSONObject sec=r.optJSONObject("section");if(sec!=null)sections.add(new Section(sec));JSONArray a=r.optJSONArray("blocks");
        Budget budget=new Budget();if(a!=null)readBlocks(a,blocks,budget,0);else {JSONArray old=r.optJSONArray("paragraphs");if(old!=null){if(old.length()>MAX_BLOCKS)throw limit("блоков");for(int i=0;i<old.length();i++){Block b=new Block();Object v=old.opt(i);b.text=v instanceof JSONObject?((JSONObject)v).optString("text",""):String.valueOf(v);budget.chars+=b.text.length();check(budget);blocks.add(b);}}}
        if(sections.isEmpty())sections.add(new Section(new JSONObject()));
    }
    private static final class Budget{int blocks,runs,chars,rows,cells;}
    private static IllegalArgumentException limit(String s){return new IllegalArgumentException("DOCX просмотр превышает ограничение: "+s);}
    private static void check(Budget b){if(b.blocks>MAX_BLOCKS)throw limit("блоков (4000)");if(b.runs>MAX_RUNS)throw limit("runs (30000)");if(b.chars>MAX_CHARS)throw limit("символов (1500000)");if(b.rows>MAX_ROWS)throw limit("строк таблиц (3000)");if(b.cells>MAX_CELLS)throw limit("ячеек таблиц (12000)");}
    private void readBlocks(JSONArray a,ArrayList<Block> out,Budget z,int depth){if(depth>MAX_DEPTH)throw limit("глубина вложенности (16)");for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;z.blocks++;check(z);Block b=new Block();b.type=str(o,"type","paragraph");b.text=str(o,"text","");z.chars+=b.text.length();JSONArray runs=o.optJSONArray("runs");if(runs!=null){for(int j=0;j<runs.length();j++){JSONObject r=runs.optJSONObject(j);if(r==null)continue;z.runs++;Run q=new Run();q.text=str(r,"text","");q.bold=r.optBoolean("bold");q.italic=r.optBoolean("italic");Object u=r.opt("underline");q.underline=(u instanceof Boolean)?((Boolean)u):(!"none".equalsIgnoreCase(String.valueOf(u))&&!"false".equalsIgnoreCase(String.valueOf(u))&&!"null".equalsIgnoreCase(String.valueOf(u)));q.size=num(r,"size_pt",11);q.color=color(str(r,"color",""));q.font=str(r,"font","");b.runs.add(q);z.chars+=q.text.length();}}
            JSONObject p=o.optJSONObject("props");b.align=str(p,"alignment","left");b.left=num(p,"indent_left_pt",0);b.right=num(p,"indent_right_pt",0);b.first=num(p,"indent_first_pt",0);b.before=num(p,"space_before_pt",0);b.after=num(p,"space_after_pt",4);JSONObject ls=p==null?null:p.optJSONObject("line_spacing");b.lineRule=str(ls,"rule","multiple");b.line=num(ls,"value",1);b.pageBreak=p!=null&&p.optBoolean("page_break_before");JSONObject li=o.optJSONObject("list");b.label=li==null?"":str(li,"label","");if("table".equals(b.type))b.table=readTable(o,z,depth+1);
            if("section".equals(b.type)){JSONObject s=o.optJSONObject("section");if(s!=null){b.section=new Section(s);sections.add(b.section);out.add(b);}}else out.add(b);check(z);
        }}
    private Table readTable(JSONObject o,Budget z,int depth){Table t=new Table();JSONArray w=o.optJSONArray("column_widths_pt");if(w!=null){t.widths=new float[w.length()];for(int i=0;i<w.length();i++)t.widths[i]=Math.max(1,(float)w.optDouble(i,72));}JSONArray rr=o.optJSONArray("rows");if(rr!=null)for(int i=0;i<rr.length();i++){z.rows++;check(z);ArrayList<Cell> row=new ArrayList<>();JSONObject ro=rr.optJSONObject(i);JSONArray cc=ro==null?null:ro.optJSONArray("cells");if(cc!=null)for(int j=0;j<cc.length();j++){JSONObject co=cc.optJSONObject(j);if(co==null)continue;z.cells++;check(z);Cell c=new Cell();c.col=Math.max(0,co.optInt("column",j));c.cs=Math.max(1,co.optInt("col_span",1));c.rs=Math.max(1,co.optInt("row_span",1));c.cont=co.optBoolean("merge_continuation");JSONArray bs=co.optJSONArray("blocks");if(bs!=null){ArrayList<Block> nested=new ArrayList<>();readBlocks(bs,nested,z,depth);for(Block b:nested){if("paragraph".equals(b.type)){if(c.text.length()>0)c.text.append('\n');c.text.append(b.text);}else if("table".equals(b.type)){t.nested=true; c.text.append("[Вложенная таблица: текстовое представление недоступно]");}}}if(c.text.length()==0)c.text.append(co.optString("plain_text",""));row.add(c);}t.rows.add(row);}return t;}
    private static int color(String s){try{if(s.isEmpty()||"null".equals(s))return Color.BLACK;if(s.matches("[0-9A-Fa-f]{6}"))s="#"+s;return Color.parseColor(s);}catch(Exception e){return Color.BLACK;}}
    private static final class Section{float w,h,l,r,t,b;Section(JSONObject o){w=num(o,"page_width_pt",612);h=num(o,"page_height_pt",792);l=num(o,"margin_left_pt",72);r=num(o,"margin_right_pt",72);t=num(o,"margin_top_pt",72);b=num(o,"margin_bottom_pt",72);boolean land="landscape".equalsIgnoreCase(str(o,"orientation",""));if(land&&h>w){float q=w;w=h;h=q;}if(!land&&w>h){float q=w;w=h;h=q;}}}
    private static final class Run{String text="",font="";boolean bold,italic,underline;float size=11;int color=Color.BLACK;}
    private static final class Block{String type="paragraph",text="",align="left",label="",lineRule="multiple";float left,right,first,before,after=4,line=1;boolean pageBreak;ArrayList<Run>runs=new ArrayList<>();Table table;Section section;}
    private static final class Table{float[]widths=new float[0];ArrayList<ArrayList<Cell>>rows=new ArrayList<>();boolean nested;}
    private static final class Cell{int col,cs=1,rs=1;boolean cont;StringBuilder text=new StringBuilder();}
    private void rebuildMode(){scroll.removeAllViews();if(textMode){TextView tv=new TextView(getContext());tv.setText(buildPlain());tv.setTextIsSelectable(true);tv.setTextSize(16);tv.setTextColor(Color.BLACK);tv.setPadding(20,16,20,16);scroll.addView(tv,new ScrollView.LayoutParams(-1,-2));}else{scroll.addView(inner,new ScrollView.LayoutParams(-1,-2));canvas.requestLayout();}}
    private String buildPlain(){StringBuilder s=new StringBuilder();String explicit=root.optString("plain_text","");if(!explicit.isEmpty())return explicit;for(Block b:blocks){if("table".equals(b.type)&&b.table!=null){for(ArrayList<Cell> row:b.table.rows){boolean first=true;for(Cell c:row){if(!first)s.append("\t");first=false;if(!c.cont)s.append(c.text);}s.append('\n');}}else{s.append(b.label);if(!b.label.isEmpty())s.append(' ');s.append(b.text).append('\n');}}return s.toString();}
    private final class PageCanvas extends View{
        final Paint p=new Paint(3);final TextPaint tp=new TextPaint(3);float scale=1,docHeight;ArrayList<Placed>placed=new ArrayList<>();
        PageCanvas(Context c){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        @Override protected void onMeasure(int ws,int hs){int w=MeasureSpec.getSize(ws);if(w>0)layoutDoc(w);setMeasuredDimension(w,Math.max(1,(int)Math.ceil(docHeight)));}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){layoutDoc(w);}
        void layoutDoc(int vw){placed.clear();if(vw<=0)return;float gap=18,top=12;Section sec=sections.get(0);float maxW=sec.w;for(Section q:sections)maxW=Math.max(maxW,q.w);scale=zoom==0?Math.min(1f,Math.max(.25f,(vw-24)/(maxW*PT))):zoom;float y=top,pageTop=y,ph=sec.h*PT*scale,pw=sec.w*PT*scale,x=Math.max(12,(vw-pw)/2),cy=pageTop+sec.t*PT*scale,avail=Math.max(32,(sec.w-sec.l-sec.r)*PT*scale);
            for(Block b:blocks){if("section".equals(b.type)){float oldEnd=pageTop+ph;sec=b.section==null?sec:b.section;pw=sec.w*PT*scale;ph=sec.h*PT*scale;avail=Math.max(32,(sec.w-sec.l-sec.r)*PT*scale);x=Math.max(12,(vw-pw)/2);pageTop=oldEnd+gap;y=pageTop;cy=pageTop+sec.t*PT*scale;continue;}if(b.pageBreak&&cy>pageTop+sec.t*PT*scale){y=pageTop+ph+gap;pageTop=y;cy=pageTop+sec.t*PT*scale;}
                float h="table".equals(b.type)&&b.table!=null?tableHeight(b.table,avail):paraHeight(b,avail);float limit=pageTop+ph-sec.b*PT*scale;if(cy+h>limit&&cy>pageTop+sec.t*PT*scale){y=pageTop+ph+gap;pageTop=y;cy=pageTop+sec.t*PT*scale;}
                x=Math.max(12,(vw-pw)/2);placed.add(new Placed(b,sec,pageTop,x,pw,ph,cy,avail,h));cy+=h+Math.max(0,b.after*PT*scale);if(cy>pageTop+ph-sec.b*PT*scale){y=Math.max(pageTop+ph,cy)+gap;pageTop=y;cy=pageTop+sec.t*PT*scale;}
            }docHeight=Math.max(24,y+ph+12);}
        float baseSize(){return 14*scale;}
        Layout.Alignment alignment(String align){if("center".equalsIgnoreCase(align))return Layout.Alignment.ALIGN_CENTER;if("right".equalsIgnoreCase(align))return Layout.Alignment.ALIGN_OPPOSITE;if("justify".equalsIgnoreCase(align)||"both".equalsIgnoreCase(align))return Layout.Alignment.ALIGN_NORMAL;return Layout.Alignment.ALIGN_NORMAL;}
        StaticLayout makeLayout(CharSequence text,int width,String align,Block b){tp.setTextSize(baseSize());StaticLayout.Builder sb=StaticLayout.Builder.obtain(text,0,text.length(),tp,Math.max(1,width)).setAlignment(alignment(align)).setIncludePad(true).setLineSpacing(0,1);if(android.os.Build.VERSION.SDK_INT>=26&&("justify".equalsIgnoreCase(align)||"both".equalsIgnoreCase(align)))sb.setJustificationMode(LineBreaker.JUSTIFICATION_MODE_INTER_WORD);if("multiple".equalsIgnoreCase(b.lineRule))sb.setLineSpacing(0,Math.max(.2f,b.line));return sb.build();}
        CharSequence styled(Block b){SpannableStringBuilder sb=new SpannableStringBuilder();if(b.runs.isEmpty())sb.append(b.text);else for(Run r:b.runs){int st=sb.length();sb.append(r.text);int en=sb.length(),style=(r.bold?1:0)|(r.italic?2:0);if(style!=0)sb.setSpan(new StyleSpan(style),st,en,33);if(r.underline)sb.setSpan(new UnderlineSpan(),st,en,33);if(r.color!=Color.BLACK)sb.setSpan(new ForegroundColorSpan(r.color),st,en,33);if(r.size>0)sb.setSpan(new AbsoluteSizeSpan(Math.max(1,(int)(r.size*PT*scale))),st,en,33);if(!r.font.isEmpty())sb.setSpan(new TypefaceSpan(r.font),st,en,33);}if(b.first!=0)sb.setSpan(new LeadingMarginSpan.Standard(Math.round(b.first*PT*scale),0),0,sb.length(),33);return sb;}
        int paraWidth(Block b,float width){return (int)Math.max(20,width-(b.left+b.right)*PT*scale);}
        float paraHeight(Block b,float width){StaticLayout sl=makeLayout(styled(b),paraWidth(b,width),b.align,b);return b.before*PT*scale+sl.getHeight();}
        float[] grid(Table t,float width){int n=t.widths.length;if(n==0){for(ArrayList<Cell>r:t.rows)for(Cell c:r)n=Math.max(n,c.col+c.cs);n=Math.max(1,n);}float[] g=new float[n+1];if(t.widths.length>0){float sum=0;for(int i=0;i<n;i++){g[i+1]=g[i]+(i<t.widths.length?t.widths[i]*PT*scale:0);sum=g[i+1];}if(sum<=0){for(int i=1;i<=n;i++)g[i]=width*i/n;}else{float f=width/sum;for(int i=1;i<=n;i++)g[i]*=f;}}else for(int i=1;i<=n;i++)g[i]=width*i/n;return g;}
        float tableHeight(Table t,float width){float[] g=grid(t,width);float[] heights=new float[t.rows.size()];for(int ri=0;ri<t.rows.size();ri++){heights[ri]=28;for(Cell c:t.rows.get(ri)){if(c.cont)continue;int end=Math.min(g.length-1,c.col+c.cs);float cw=Math.max(20,g[end]-g[Math.min(c.col,g.length-1)]-8);StaticLayout sl=makeLayout(c.text.toString(),(int)cw,"left",new Block());heights[ri]=Math.max(heights[ri],sl.getHeight()+8);}}for(int ri=0;ri<t.rows.size();ri++)for(Cell c:t.rows.get(ri))if(!c.cont&&c.rs>1){int end=Math.min(heights.length,ri+c.rs);float have=0;for(int r=ri;r<end;r++)have+=heights[r];if(end>ri&&have<heights[ri])heights[ri]+=heights[ri]-have;}float sum=0;for(float h:heights)sum+=h;return sum;}
        @Override protected void onDraw(Canvas c){super.onDraw(c);Rect clip=new Rect();c.getClipBounds(clip);int save=c.save();c.clipRect(clip);float lastTop=Float.NaN;for(Placed a:placed)if(a.y!=lastTop){lastTop=a.y;if(a.y+a.h>=clip.top&&a.y<=clip.bottom)drawPaper(c,a);}for(Placed a:placed)if(a.contentY+a.contentHeight>=clip.top&&a.contentY<=clip.bottom)drawContent(c,a);c.restoreToCount(save);}
        void drawPaper(Canvas c,Placed a){p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);c.drawRect(a.x,a.y,a.x+a.w,a.y+a.h,p);p.setStyle(Paint.Style.STROKE);p.setColor(Color.rgb(200,205,214));c.drawRect(a.x,a.y,a.x+a.w,a.y+a.h,p);p.setStyle(Paint.Style.FILL);if(rulerVisible){p.setColor(Color.rgb(244,246,250));c.drawRect(a.x,a.y,a.x+a.w,a.y+18,p);p.setColor(Color.GRAY);for(int xx=0;xx<a.w;xx+=12)c.drawLine(a.x+xx,a.y+13,a.x+xx,a.y+(xx%60==0?6:10),p);}}
        void drawContent(Canvas c,Placed a){float x=a.x+a.section.l*PT*scale,y=a.contentY,width=a.available;Block b=a.block;if(b.table!=null)drawTable(c,b.table,x,y,width);else{float left=b.left*PT*scale,hang=Math.min(0,b.first*PT*scale);StaticLayout sl=makeLayout(styled(b),paraWidth(b,width),b.align,b);c.save();c.translate(x+left,y+b.before*PT*scale);sl.draw(c);c.restore();if(!b.label.isEmpty()){tp.setTextSize(baseSize());tp.setColor(Color.BLACK);float labelW=tp.measureText(b.label),labelX=x+b.left*PT*scale+hang-labelW-6;c.drawText(b.label,labelX,y+b.before*PT*scale+baseSize(),tp);}}}
        void drawTable(Canvas c,Table t,float x,float y,float width){float[] g=grid(t,width);float[] rh=new float[t.rows.size()];for(int i=0;i<rh.length;i++)rh[i]=28;for(int ri=0;ri<t.rows.size();ri++)for(Cell cell:t.rows.get(ri))if(!cell.cont){int e=Math.min(g.length-1,cell.col+cell.cs);float cw=Math.max(20,g[e]-g[Math.min(cell.col,g.length-1)]-8);StaticLayout l=makeLayout(cell.text.toString(),(int)cw,"left",new Block());rh[ri]=Math.max(rh[ri],l.getHeight()+8);}for(int ri=0;ri<t.rows.size();ri++)for(Cell ce:t.rows.get(ri))if(!ce.cont&&ce.rs>1){float sum=0;for(int k=ri;k<Math.min(rh.length,ri+ce.rs);k++)sum+=rh[k];if(sum<rh[ri])rh[ri]+=rh[ri]-sum;}float yy=y;for(int ri=0;ri<t.rows.size();ri++){for(Cell ce:t.rows.get(ri)){if(ce.cont)continue;int e=Math.min(g.length-1,ce.col+ce.cs);float xx=x+g[Math.min(ce.col,g.length-1)],ww=Math.max(1,g[e]-g[Math.min(ce.col,g.length-1)]);float hh=0;for(int k=ri;k<Math.min(rh.length,ri+ce.rs);k++)hh+=rh[k];p.setColor(Color.rgb(90,100,115));p.setStyle(Paint.Style.STROKE);c.drawRect(xx,yy,xx+ww,yy+hh,p);p.setStyle(Paint.Style.FILL);StaticLayout l=makeLayout(ce.text.toString(),(int)Math.max(1,ww-10),"left",new Block());c.save();c.clipRect(xx+4,yy+4,xx+ww-4,yy+hh-4);c.translate(xx+5,yy+4);l.draw(c);c.restore();}yy+=rh[ri];}}
    }
    private static final class Placed{Block block;Section section;float y,x,w,h,contentY,contentHeight,available;Placed(Block b,Section s,float y,float x,float w,float h,float cy,float av,float contentHeight){block=b;section=s;this.y=y;this.x=x;this.w=w;this.h=h;contentY=cy;available=av;this.contentHeight=contentHeight;}}
}
