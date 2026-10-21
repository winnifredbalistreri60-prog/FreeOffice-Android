package ru.freeoffice.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only plain-text viewer. Owner supplies navigation and edit/encoding actions. */
public final class TextViewer {
    private final Activity activity;
    private final LinearLayout host;
    private final String text, name;
    private final JSONObject settings;
    private final Runnable back, edit, encoding;
    private LinearLayout root;
    private TextView content;
    private ScrollView scroll;
    private android.widget.HorizontalScrollView horizontal;
    private TextView status;
    private String query = "";
    private int hit = -1, hitEnd = -1, fontSp = 16;
    private boolean wrap = true, started, closed;

    public TextViewer(Activity activity, LinearLayout host, String text, String name,
                      JSONObject settings, Runnable back, Runnable edit, Runnable encoding) {
        if (activity == null || host == null) throw new IllegalArgumentException("Missing viewer argument");
        this.activity=activity; this.host=host; this.text=text==null?"":text; this.name=name==null?"":name;
        this.settings=settings==null?new JSONObject():settings; this.back=back; this.edit=edit; this.encoding=encoding;
    }
    public void start() {
        if(closed)return; started=true; host.removeAllViews(); root=new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.WHITE); host.addView(root,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout h=new LinearLayout(activity); h.setGravity(Gravity.CENTER_VERTICAL); h.setBackgroundColor(0xff202124);
        Button b=button("←","Назад",48); b.setTextColor(Color.WHITE); b.setOnClickListener(v->{if(!closed&&back!=null)back.run();}); h.addView(b,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView title=new TextView(activity); title.setText(name); title.setTextSize(17); title.setTypeface(null,Typeface.BOLD); title.setTextColor(Color.WHITE); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); h.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
        Button find=button("⌕","Найти",48); find.setTextColor(Color.WHITE); find.setOnClickListener(v->{if(!closed)findDialog();}); h.addView(find,new LinearLayout.LayoutParams(dp(48),dp(48)));
        Button more=button("⋮","Меню",48); more.setTextColor(Color.WHITE); more.setTextSize(22); more.setOnClickListener(v->{if(!closed)menu(v);}); h.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48))); root.addView(h);
        scroll=new ScrollView(activity); scroll.setFillViewport(false); content=new TextView(activity); content.setTextColor(Color.BLACK); content.setTextIsSelectable(true); content.setTextSize(fontSp); content.setPadding(dp(12),dp(10),dp(12),dp(12)); content.setLineSpacing(0,1); horizontal=new android.widget.HorizontalScrollView(activity);horizontal.setFillViewport(true);horizontal.addView(content,new android.widget.HorizontalScrollView.LayoutParams(-1,-2));scroll.addView(horizontal,new ScrollView.LayoutParams(-1,-2)); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));scroll.addOnLayoutChangeListener((v,l,t,r,bottom,ol,ot,oldRight,ob)->{if(r-l!=oldRight-ol&&wrap)render();}); render();
        status=new TextView(activity); status.setText(settings.optString("warning","")); status.setTextColor(0xff7a4f00); status.setTextSize(12); status.setPadding(dp(8),dp(3),dp(8),dp(3)); status.setMaxLines(2); root.addView(status,new LinearLayout.LayoutParams(-1,-2));
    }
    private Button button(String s,String desc,int height){Button b=new Button(activity);b.setText(s);b.setAllCaps(false);b.setContentDescription(desc);b.setMinHeight(dp(height));b.setMinimumHeight(dp(height));return b;}
    private void menu(View anchor){if(closed)return;android.widget.PopupMenu m=new android.widget.PopupMenu(activity,anchor);m.getMenu().add("Найти…");m.getMenu().add("Найти далее").setEnabled(!query.isEmpty());m.getMenu().add("Перенос строк: "+(wrap?"вкл":"выкл"));m.getMenu().add("Мельче (−)");m.getMenu().add("Крупнее (+)");m.getMenu().add("100% / размер по умолчанию");m.getMenu().add("Кодировка…").setEnabled(encoding!=null);if(edit!=null)m.getMenu().add("Редактировать…");m.setOnMenuItemClickListener(i->{if(closed)return true;String s=i.getTitle().toString();if(s.equals("Найти…")){findDialog();}else if(s.equals("Найти далее")){findNext();}else if(s.startsWith("Перенос")){wrap=!wrap;render();}else if(s.startsWith("Мельче")){fontSp=Math.max(8,fontSp-1);render();}else if(s.startsWith("Крупнее")){fontSp=Math.min(40,fontSp+1);render();}else if(s.startsWith("100%")){fontSp=16;render();}else if(s.startsWith("Кодировка")){if(encoding!=null)encoding.run();}else if(s.startsWith("Редактировать")&&edit!=null)edit.run();return true;});m.show();}
    private void findDialog(){if(closed)return;EditText input=new EditText(activity);input.setSingleLine(true);input.setHint("Текст для поиска");input.setText(query);new AlertDialog.Builder(activity).setTitle("Найти").setView(input).setNegativeButton("Отмена",null).setPositiveButton("Далее",(d,w)->{if(closed)return;query=input.getText().toString();hit=-1;hitEnd=-1;if(query.isEmpty()){status.setText(settings.optString("warning",""));render();return;}findNext();}).show();}
    private void findNext(){
        if(closed||query.isEmpty())return;
        Matcher matcher=Pattern.compile(Pattern.quote(query),Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(text);
        int next=-1, nextEnd=-1;
        int from=hit<0?0:Math.min(text.length(),hit+Math.max(1,hitEnd-hit));
        if(matcher.find(from)){next=matcher.start();nextEnd=matcher.end();}
        else if(matcher.find(0)){next=matcher.start();nextEnd=matcher.end();}
        if(next<0){hit=-1;hitEnd=-1;render();String warning=settings.optString("warning","");status.setText(warning.isEmpty()?"Совпадений нет":warning+" · Совпадений нет");return;}
        hit=next;hitEnd=nextEnd;status.setText(settings.optString("warning",""));render();
        final int target=hit;content.post(()->{if(closed||content==null||scroll==null||hit!=target||target<0||target>=text.length())return;android.text.Layout l=content.getLayout();if(l!=null){if(!wrap)horizontal.smoothScrollTo(Math.max(0,(int)l.getPrimaryHorizontal(target)-dp(24)),0);scroll.smoothScrollTo(0,Math.max(0,l.getLineTop(l.getLineForOffset(target))-dp(32)));}});
    }
    private void render(){if(closed||content==null)return;content.setTextSize(fontSp);content.setHorizontallyScrolling(!wrap);if(horizontal!=null){horizontal.setHorizontalScrollBarEnabled(!wrap);content.setLayoutParams(new android.widget.HorizontalScrollView.LayoutParams(wrap?Math.max(1,scroll.getWidth()):-2,-2));if(wrap)horizontal.scrollTo(0,0);}if(query.length()>0&&hit>=0&&hitEnd>hit&&hitEnd<=text.length()){SpannableString s=new SpannableString(text);s.setSpan(new BackgroundColorSpan(0xffffdf66),hit,hitEnd,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);content.setText(s);}else content.setText(text);}
    private int dp(float n){return (int)(n*activity.getResources().getDisplayMetrics().density+.5f);}
    public void close(){closed=true;}
}
