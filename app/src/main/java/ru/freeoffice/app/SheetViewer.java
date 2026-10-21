package ru.freeoffice.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.TypedValue;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only, bounded-page spreadsheet viewer. All backend work is serialized off the UI thread. */
public final class SheetViewer {
    public interface Backend { JSONObject call(JSONObject request) throws Exception; }

    private static final int PAGE_ROWS = 40, PAGE_COLS = 10;
    private final Activity activity;
    private final LinearLayout host;
    private final String fileName;
    private final JSONObject opened;
    private final Backend backend;
    private final Runnable onBack, onEdit;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<String> sheetNames = new ArrayList<>();
    private final List<Integer> sheetRows = new ArrayList<>(), sheetCols = new ArrayList<>();
    private LinearLayout root, toolbar, grid;
    private SheetScrollView vertical;
    private HorizontalScrollView horizontal;
    private JSONObject cachedPage;
    private float zoom = 1f, lastPinchX, lastPinchY;
    private TextView zoomLabel;
    private Spinner sheetSpinner;
    private TextView status, pageLabel;
    private Button editButton;
    private int selectedRow=-1, selectedCol=-1;
    private Button prevRows, nextRows, prevCols, nextCols, findButton, nextFindButton, goButton, retryButton;
    private int sheetIndex, rowStart, colStart;
    private long generation;
    private boolean busy, started, closed;
    private JSONObject lastPageRequest;
    private String lastQuery;
    private int lastHitRow = -1, lastHitCol = -1;
    private final List<Button> toolButtons = new ArrayList<>();
    private View selectedCell;
    private int pageScrollX, pageScrollY;
    private boolean revealPageSelection;
    private long viewportRevision;
    private Runnable importOptions;
    private String persistentWarning = "";
    /** Auto-fit: column width in dp at zoom 1, per sheet; grows to the widest text seen, never shrinks while the sheet is open. */
    private static final float MIN_COL_DP = 48f, MAX_COL_DP = 220f, MAX_LAST_COL_DP = 640f, CELL_PAD_DP_CLASSIC = 40f, CELL_PAD_DP_COMPACT = 12f; // classic: edit_text insets + 6dp padding; compact: 4dp+4dp padding + 1px lines + margin
    private final Map<Long, Float> colWidthDp = new HashMap<>();
    private Paint measurePaint;
    private boolean autoFit = true;
    private Button autoFitButton;

    public SheetViewer(Activity activity, LinearLayout host, String fileName, JSONObject opened,
                       Backend backend, Runnable onBack, Runnable onEdit) {
        if (activity == null || host == null || opened == null || backend == null) throw new IllegalArgumentException("Missing viewer argument");
        this.activity = activity; this.host = host; this.fileName = fileName == null ? "" : fileName;
        this.opened = opened; this.backend = backend; this.onBack = onBack; this.onEdit = onEdit;
        JSONArray sheets = opened.optJSONArray("sheets");
        if (sheets != null) for (int i = 0; i < sheets.length(); i++) {
            JSONObject s = sheets.optJSONObject(i); if (s == null) continue;
            sheetNames.add(s.optString("name", "Лист " + (i + 1)));
            sheetRows.add(Math.max(1, s.optInt("rows", 1)));
            sheetCols.add(Math.max(1, s.optInt("cols", 1)));
        }
        if (sheetNames.isEmpty()) { sheetNames.add("Лист 1"); sheetRows.add(1); sheetCols.add(1); }
    }

    /** Creates the viewer and requests its first bounded page. Call on the UI thread. */
    public void start() {
        if (closed) return;
        started = true;
        host.removeAllViews();
        hideAppChrome(true);
        root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.WHITE);
        host.addView(root, new LinearLayout.LayoutParams(-1, -1));
        buildHeader();
        buildSheetTabs();
        toolbar = new LinearLayout(activity); toolbar.setOrientation(LinearLayout.VERTICAL);
        vertical = new SheetScrollView(activity); vertical.setFillViewport(false);
        horizontal = new HorizontalScrollView(activity); horizontal.setFillViewport(false);
        grid = new LinearLayout(activity); grid.setOrientation(LinearLayout.VERTICAL);
        horizontal.addView(grid, new HorizontalScrollView.LayoutParams(-2, -2)); vertical.addView(horizontal, new ScrollView.LayoutParams(-1, -1));
        root.addView(vertical, new LinearLayout.LayoutParams(-1, 0, 1));
        status = new TextView(activity); status.setPadding(dp(8), dp(2), dp(8), dp(2)); status.setTextColor(Color.DKGRAY); status.setTextSize(11); status.setMaxLines(2);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        buildToolbar(); renderEmpty("Загрузка…"); requestPage();
    }

    /** The activity title/subtitle take a third of a phone screen; hide them while the sheet is open and restore on close. */
    private final List<View> hiddenChrome = new ArrayList<>();
    private void hideAppChrome(boolean hide) {
        if (hide) {
            if (!(host.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) host.getParent();
            for (int i = 0; i < parent.getChildCount(); i++) { View v = parent.getChildAt(i); if (v != host && v.getVisibility() == View.VISIBLE) { v.setVisibility(View.GONE); hiddenChrome.add(v); } }
        } else { for (View v : hiddenChrome) v.setVisibility(View.VISIBLE); hiddenChrome.clear(); }
    }

    private Button iconButton(String text, String description, View.OnClickListener action) {
        Button b = button(text); b.setTextSize(18); b.setMinimumWidth(0); b.setMinWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0); b.setBackgroundColor(Color.TRANSPARENT); b.setContentDescription(description); b.setOnClickListener(action); return b;
    }

    private void buildHeader() {
        LinearLayout header = new LinearLayout(activity); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0xff202124);
        Button back = iconButton("←", "Назад", v -> { if (onBack != null) onBack.run(); }); back.setTextColor(Color.WHITE);
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = new TextView(activity); title.setText(fileName); title.setTextSize(17); title.setTextColor(Color.WHITE); title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); title.setPadding(dp(4), 0, dp(4), 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button find = iconButton("🔍", "Найти", v -> { if (!busy) showFindDialog(); }); find.setTextColor(Color.WHITE); find.setTextSize(16);
        header.addView(find, new LinearLayout.LayoutParams(dp(44), dp(48))); toolButtons.add(find); findButton = find;
        Button more = iconButton("⋮", "Меню", v -> showMenu(v)); more.setTextColor(Color.WHITE); more.setTextSize(22);
        header.addView(more, new LinearLayout.LayoutParams(dp(44), dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    private static final int M_GO = 1, M_FIND = 2, M_NEXT = 3, M_ZOOM_IN = 4, M_ZOOM_OUT = 5, M_ZOOM_100 = 6, M_FIT = 7, M_AUTOFIT = 8, M_EDIT = 9, M_INFO = 10;
    private void showMenu(View anchor) {
        if (busy || closed) return;
        android.widget.PopupMenu menu = new android.widget.PopupMenu(activity, anchor);
        android.view.Menu m = menu.getMenu();
        m.add(0, M_GO, 0, "К ячейке…"); m.add(0, M_FIND, 1, "Найти…");
        android.view.MenuItem next = m.add(0, M_NEXT, 2, "Найти далее"); next.setEnabled(lastQuery != null && !lastQuery.isEmpty());
        m.add(0, M_ZOOM_IN, 3, "Крупнее (+)"); m.add(0, M_ZOOM_OUT, 4, "Мельче (−)"); m.add(0, M_ZOOM_100, 5, "Масштаб 100% (сейчас " + Math.round(zoom * 100) + "%)");
        m.add(0, M_FIT, 6, "По ширине области");
        android.view.MenuItem fitText = m.add(0, M_AUTOFIT, 7, "Ширина по тексту"); fitText.setCheckable(true); fitText.setChecked(autoFit);
        if (onEdit != null) m.add(0, M_EDIT, 8, "Редактировать копию…");
        m.add(0, M_INFO, 9, "О просмотре");
        if (importOptions != null) m.add(0, 11, 10, "Кодировка и разделитель…");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case M_GO: showGoDialog(); return true;
                case M_FIND: showFindDialog(); return true;
                case M_NEXT: findNext(); return true;
                case M_ZOOM_IN: zoomAt(zoom * 1.25f, false); return true;
                case M_ZOOM_OUT: zoomAt(zoom / 1.25f, false); return true;
                case M_ZOOM_100: zoomAt(1f, false); return true;
                case M_FIT: fitWidth(); return true;
                case M_AUTOFIT: toggleAutoFit(); return true;
                case M_EDIT: if (!busy) onEdit.run(); return true;
                case M_INFO: new AlertDialog.Builder(activity).setTitle("Просмотр").setMessage("Исходник не изменяется. Формулы не пересчитываются. Оформление файла (шрифты, заливки, рамки, ширина столбцов из файла) не воспроизводится; ширина столбцов подбирается по тексту. Нажмите ячейку, чтобы увидеть полный текст.").setPositiveButton("OK", null).show(); return true;
                case 11: if (importOptions != null) importOptions.run(); return true;
            }
            return false;
        });
        menu.show();
    }
    /** Set before start to expose import encoding and delimiter options in the menu. */
    public void setImportOptions(Runnable callback) { importOptions = callback; }
    /** Retains an import warning so it remains visible together with subsequent page status. */
    public void setWarning(String warning) { persistentWarning = warning == null ? "" : warning.trim(); }
    private void toggleAutoFit() {
        if (busy || closed) return; autoFit = !autoFit; colWidthDp.clear(); updateAutoFitButton();
        if (cachedPage != null) { renderPage(cachedPage); positionViewport(horizontal.getScrollX(), vertical.getScrollY(), false); }
    }

    private final List<TextView> sheetTabs = new ArrayList<>();
    private void buildSheetTabs() {
        HorizontalScrollView strip = new HorizontalScrollView(activity); strip.setHorizontalScrollBarEnabled(false); strip.setBackgroundColor(0xff2b2c2f);
        LinearLayout tabs = new LinearLayout(activity); tabs.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < sheetNames.size(); i++) {
            final int index = i;
            TextView tab = new TextView(activity); tab.setText(sheetNames.get(i)); tab.setTextSize(14); tab.setSingleLine(true);
            tab.setGravity(Gravity.CENTER); tab.setPadding(dp(14), 0, dp(14), 0); tab.setMinHeight(dp(40));
            tab.setContentDescription("Лист " + sheetNames.get(i)); tab.setFocusable(true); tab.setClickable(true);
            tab.setOnClickListener(v -> selectSheet(index));
            tabs.addView(tab, new LinearLayout.LayoutParams(-2, dp(40))); sheetTabs.add(tab);
        }
        strip.addView(tabs); root.addView(strip, new LinearLayout.LayoutParams(-1, -2)); updateSheetTabs();
    }
    private void selectSheet(int position) {
        if (busy || closed || sheetIndex == position) return;
        sheetIndex = position; rowStart = colStart = 0; lastQuery = null; lastHitRow = lastHitCol = -1; selectedRow = selectedCol = -1; updateSheetTabs(); requestPage();
    }
    private void updateSheetTabs() {
        for (int i = 0; i < sheetTabs.size(); i++) { TextView t = sheetTabs.get(i); boolean on = i == sheetIndex;
            t.setTextColor(on ? 0xff81c995 : 0xffe8eaed); t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL); t.setSelected(on); }
    }

    private void buildToolbar() {
        LinearLayout navigation = toolRow(); navigation.setBackgroundColor(0xfff1f3f4);
        prevRows = addTool(navigation, "▲", v -> moveRows(-PAGE_ROWS));
        nextRows = addTool(navigation, "▼", v -> moveRows(PAGE_ROWS));
        pageLabel = new TextView(activity); pageLabel.setTextSize(11); pageLabel.setGravity(Gravity.CENTER); pageLabel.setTextColor(Color.DKGRAY); pageLabel.setMaxLines(2);
        navigation.addView(pageLabel, new LinearLayout.LayoutParams(0, -2, 3));
        prevCols = addTool(navigation, "◀", v -> moveCols(-PAGE_COLS));
        nextCols = addTool(navigation, "▶", v -> moveCols(PAGE_COLS));
        prevRows.setContentDescription("Предыдущие 40 строк"); nextRows.setContentDescription("Следующие 40 строк");
        prevCols.setContentDescription("Предыдущие 10 столбцов"); nextCols.setContentDescription("Следующие 10 столбцов");
        zoomLabel = new TextView(activity); zoomLabel.setTextSize(11); zoomLabel.setTextColor(Color.DKGRAY); zoomLabel.setPadding(dp(4), 0, dp(8), 0); navigation.addView(zoomLabel); updateZoomLabel();
        retryButton = addTool(toolRow(), "Повторить", v -> { if (lastPageRequest != null) request(lastPageRequest, true); else requestPage(); });
        setRetryVisible(false);
    }

    private void updateAutoFitButton() { if (autoFitButton != null) autoFitButton.setText(autoFit ? "По тексту ✓" : "По тексту"); }
    // Compact grid (default with auto-fit), modelled on stock mobile spreadsheet viewers: 12sp text, 24dp rows, thin lines.
    private float textSp() { return autoFit ? 12f : 14f; }
    private float rowDp() { return autoFit ? 24f : 42f; }
    private float labelDp() { return autoFit ? 36f : 52f; }
    private float padDp() { return autoFit ? CELL_PAD_DP_COMPACT : CELL_PAD_DP_CLASSIC; }
    private float colDp(int col) {
        if (!autoFit) return 120f;
        Float w = colWidthDp.get(((long) sheetIndex << 32) | (col & 0xffffffffL));
        return w == null ? 120f : w;
    }
    /** Measures header letters and page values once per page at zoom 1 (14sp), widening cached column widths. */
    private void measurePage(int firstCol, JSONArray data) {
        if (measurePaint == null) measurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        measurePaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSp(), activity.getResources().getDisplayMetrics()));
        float density = activity.getResources().getDisplayMetrics().density;
        // Title/separator rows (a single non-empty cell, like "Цены актуальны…" or "━━ model ━━") would
        // overflow into empty neighbours in a desktop spreadsheet; they must not widen the whole column.
        boolean[] skip = new boolean[data == null ? 0 : Math.min(PAGE_ROWS, data.length())];
        for (int r = 0; r < skip.length; r++) {
            JSONArray values = data.optJSONArray(r); int filled = 0;
            if (values != null) for (int c = 0; c < values.length(); c++) if (!values.optString(c, "").isEmpty()) filled++;
            skip[r] = filled <= 1 && sheetCols.get(sheetIndex) > 1;
        }
        for (int c = 0; c < PAGE_COLS && firstCol + c < sheetCols.get(sheetIndex); c++) {
            int index = firstCol + c;
            float max = 0;
            measurePaint.setTypeface(Typeface.DEFAULT_BOLD); float headerW = measurePaint.measureText(colName(index));
            measurePaint.setTypeface(Typeface.DEFAULT);
            List<Float> widths = new ArrayList<>();
            if (data != null) for (int r = 0; r < Math.min(PAGE_ROWS, data.length()); r++) {
                if (skip[r]) continue;
                JSONArray values = data.optJSONArray(r); if (values == null || c >= values.length()) continue;
                String text = values.optString(c, ""); if (text.isEmpty()) continue;
                int nl = text.indexOf('\n'); if (nl >= 0) text = text.substring(0, nl);
                if (text.length() > 200) text = text.substring(0, 200); // beyond MAX_COL_DP anyway
                widths.add(measurePaint.measureText(text));
            }
            // ~90th percentile: a few very long cells do not widen the column; they spill or get an ellipsis.
            if (!widths.isEmpty()) { java.util.Collections.sort(widths); max = widths.get(Math.min(widths.size() - 1, (int) Math.floor(widths.size() * 0.9))); }
            max = Math.max(max, headerW);
            // The sheet's last column has no neighbour to spill into, so it may grow wider (long notes/URLs).
            float cap = index == sheetCols.get(sheetIndex) - 1 ? MAX_LAST_COL_DP : MAX_COL_DP;
            float dpWidth = Math.max(MIN_COL_DP, Math.min(cap, max / density + padDp()));
            long key = ((long) sheetIndex << 32) | (index & 0xffffffffL);
            Float old = colWidthDp.get(key);
            if (old == null || dpWidth > old) colWidthDp.put(key, dpWidth);
        }
    }
    private static String valueAt(JSONArray values, int c) { return values != null && c < values.length() ? values.optString(c, "") : ""; }
    private float textDp(String text) {
        if (measurePaint == null) return 0;
        int nl = text.indexOf('\n'); if (nl >= 0) text = text.substring(0, nl);
        if (text.length() > 200) text = text.substring(0, 200);
        measurePaint.setTypeface(Typeface.DEFAULT);
        return measurePaint.measureText(text) / activity.getResources().getDisplayMetrics().density;
    }
    private void updateZoomLabel() { if (zoomLabel != null) zoomLabel.setText(Math.round(zoom * 100) + "%"); }
    private void zoomAt(float requested, boolean pinch) {
        if (closed || busy || grid == null || vertical == null || horizontal == null) return;
        float next = Math.max(.1f, Math.min(2.5f, requested)); if (Math.abs(next - zoom) < .001f) return;
        float old = zoom, screenX = pinch ? lastPinchX : horizontal.getWidth()/2f, screenY = pinch ? lastPinchY : vertical.getHeight()/2f;
        float focusX = horizontal.getScrollX() + screenX, focusY = vertical.getScrollY() + screenY;
        zoom = next; updateZoomLabel(); if (cachedPage != null) renderPage(cachedPage);
        positionViewport(Math.max(0, Math.round(focusX * next / old - screenX)),
                Math.max(0, Math.round(focusY * next / old - screenY)), false);
    }
    private void fitWidth() {
        if (closed || busy || horizontal == null || horizontal.getWidth() <= 0) return;
        int count = Math.min(PAGE_COLS, Math.max(0, sheetCols.get(sheetIndex) - colStart));
        float available = horizontal.getWidth() / activity.getResources().getDisplayMetrics().density;
        float total = labelDp(); for (int c = 0; c < count; c++) total += colDp(colStart + c);
        float target = available / total;
        zoomAt(Math.max(.1f, Math.min(2.5f, target)), false);
        status.setText("По ширине текущей области столбцов, не всего листа."+(target<.5f?" Обзорный масштаб: увеличьте для чтения.":""));
    }
    private final class SheetScrollView extends ScrollView {
        private final ScaleGestureDetector scale; private boolean multiTouch;private float requestedZoom,beginZoom;
        SheetScrollView(Context c) { super(c); scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) {if(busy||closed)return false;lastPinchX=d.getFocusX(); lastPinchY=d.getFocusY();beginZoom=requestedZoom=zoom;grid.setPivotX(horizontal.getScrollX()+lastPinchX);grid.setPivotY(vertical.getScrollY()+lastPinchY);return true; }
            @Override public boolean onScale(ScaleGestureDetector d) {lastPinchX=d.getFocusX();lastPinchY=d.getFocusY();requestedZoom=Math.max(.1f,Math.min(2.5f,requestedZoom*d.getScaleFactor()));grid.setScaleX(requestedZoom/beginZoom);grid.setScaleY(requestedZoom/beginZoom);zoomLabel.setText(Math.round(requestedZoom*100)+"%");return true; }
            @Override public void onScaleEnd(ScaleGestureDetector d){grid.setScaleX(1);grid.setScaleY(1);zoomAt(requestedZoom,true);updateZoomLabel();}
        }); }
        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            int a=e.getActionMasked();
            if (a==MotionEvent.ACTION_POINTER_DOWN) { multiTouch=true; MotionEvent cancel=MotionEvent.obtain(e); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle(); }
            if (multiTouch) { scale.onTouchEvent(e); if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL)multiTouch=false; return true; }
            scale.onTouchEvent(e); return super.dispatchTouchEvent(e);
        }
    }

    private LinearLayout toolRow() {
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(row, new LinearLayout.LayoutParams(-1, -2)); return row;
    }
    private Button addTool(LinearLayout row, String text, View.OnClickListener action) {
        Button b = button(text); b.setTextSize(12); b.setMinimumWidth(0); b.setMinWidth(0);
        b.setPadding(dp(2), 0, dp(2), 0); b.setMaxLines(2); b.setMinHeight(dp(44)); b.setMinimumHeight(dp(44));
        b.setOnClickListener(action); row.addView(b, new LinearLayout.LayoutParams(0, -2, 1)); toolButtons.add(b); return b;
    }
    private void setRetryVisible(boolean visible) {
        retryButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        ((View) retryButton.getParent()).setVisibility(visible ? View.VISIBLE : View.GONE);
    }
    private Button button(String text) { Button b = new Button(activity); b.setText(text); b.setAllCaps(false); return b; }
    private int dp(float n) { return (int)(n * activity.getResources().getDisplayMetrics().density + .5f); }

    private JSONObject pageRequest() throws Exception {
        return new JSONObject().put("op", "sheet_view_page").put("session", opened.optString("session"))
                .put("sheet", sheetIndex).put("row", rowStart).put("col", colStart).put("rows", PAGE_ROWS).put("cols", PAGE_COLS);
    }
    private void requestPage() { requestPage(0, 0, false); }
    private void requestPage(int scrollX, int scrollY, boolean revealSelection) {
        if (!started || closed || busy) return;
        pageScrollX = scrollX; pageScrollY = scrollY; revealPageSelection = revealSelection;
        try { lastPageRequest = pageRequest(); request(lastPageRequest, false); }
        catch (Exception e) { showError(e.getMessage()); }
    }
    private void request(JSONObject req, boolean retry) {
        if (closed || busy) return;
        busy = true; viewportRevision++; setControlsEnabled(false); status.setText((persistentWarning.isEmpty()?"":persistentWarning+" ")+(retry ? "Повторный запрос…" : "Загрузка…")); setRetryVisible(false);
        final long token = ++generation;
        worker.execute(() -> {
            JSONObject result = null; Exception failure = null;
            try { result = backend.call(new JSONObject(req.toString())); if (result == null || !result.optBoolean("ok", false)) throw new Exception(result == null ? "Пустой ответ" : result.optString("error", "Не удалось загрузить страницу")); }
            catch (Exception e) { failure = e; }
            JSONObject answer = result; Exception error = failure;
            activity.runOnUiThread(() -> { if (closed || token != generation) return; busy = false; setControlsEnabled(true);
                if (error != null) { cachedPage = null; selectedCell = null; renderEmpty("Страница недоступна"); showError(error.getMessage()); }
                else { renderPage(answer); positionViewport(pageScrollX, pageScrollY, revealPageSelection); }
            });
        });
    }
    private void renderPage(JSONObject response) {
        cachedPage = response;
        int rows = Math.max(1, sheetRows.get(sheetIndex)), cols = Math.max(1, sheetCols.get(sheetIndex));
        int actualRow = Math.max(0, response.optInt("row", rowStart)), actualCol = Math.max(0, response.optInt("col", colStart));
        JSONArray data = response.optJSONArray("rows"); measurePage(actualCol, data); selectedCell = null; grid.removeAllViews(); addGridRow(-1, actualCol, null, true);
        int rendered = 0;
        if (data != null) for (int r = 0; r < Math.min(PAGE_ROWS, data.length()); r++) {
            JSONArray values = data.optJSONArray(r); addGridRow(actualRow + r, actualCol, values, false); rendered++;
        }
        if (rendered == 0) addGridRow(actualRow, actualCol, new JSONArray(), false);
        int totalRows = Math.max(rows, response.optInt("totalRows", rows)), totalCols = Math.max(cols, response.optInt("totalCols", cols));
        rowStart = actualRow; colStart = actualCol;
        int endRow = Math.min(totalRows, actualRow + Math.max(1, rendered));
        int endCol = Math.min(totalCols, actualCol + PAGE_COLS);
        pageLabel.setText("Строки " + (actualRow + 1) + "–" + endRow + " из " + totalRows + "; столбцы " + colName(actualCol) + "–" + colName(Math.max(actualCol, endCol - 1)) + " из " + colName(totalCols - 1));
        String warning = response.optString("warning", "");
        status.setText((persistentWarning.isEmpty()?"":persistentWarning+" ")+(selectedRow>=0?"Выбрана "+colName(selectedCol)+(selectedRow+1)+". ":"")+(warning.isEmpty() ? "Лист доступен по страницам. Нажмите ячейку для полного текста." : warning)+(response.optBoolean("truncated",false)?" Длинный текст в сетке сокращён; откройте ячейку касанием.":""));
        setRetryVisible(false); updateNavigation(totalRows, totalCols);
    }
    /** Scroll after layout, using actual cell bounds; ignore callbacks from older pages/zooms. */
    private void positionViewport(int scrollX, int scrollY, boolean revealSelection) {
        final long revision = ++viewportRevision, token = generation;
        final View target = revealSelection ? selectedCell : null;
        grid.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (grid.getViewTreeObserver().isAlive()) grid.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (closed || revision != viewportRevision || token != generation) return;
                int x = scrollX, y = scrollY;
                if (target != null && target.getParent() instanceof View) {
                    View line = (View) target.getParent();
                    x = Math.max(0, target.getLeft() - Math.max(0, (horizontal.getWidth() - target.getWidth()) / 2));
                    y = Math.max(0, line.getTop() - Math.max(0, (vertical.getHeight() - target.getHeight()) / 2));
                }
                horizontal.scrollTo(x, 0); vertical.scrollTo(0, y);
            }
        });
        grid.requestLayout();
    }
    private void addGridRow(int rowNum, int colStart, JSONArray values, boolean header) {
        LinearLayout line = new LinearLayout(activity); line.setOrientation(LinearLayout.HORIZONTAL);
        TextView label = cell(header ? "" : Integer.toString(rowNum + 1), true); line.addView(label, new LinearLayout.LayoutParams(dp(labelDp() * zoom), dp(rowDp() * zoom)));
        int limit = Math.min(PAGE_COLS, Math.max(0, sheetCols.get(sheetIndex) - colStart));
        for (int c = 0; c < limit; c++) {
            int index = colStart + c; String text = header ? colName(index) : valueAt(values, c);
            float widthDp = colDp(index);
            int span = 1;
            // Spill long text into following EMPTY cells (desktop-spreadsheet style) instead of widening the column.
            if (!header && autoFit && !text.isEmpty() && !(rowNum == selectedRow && index == selectedCol)) {
                float need = textDp(text) + padDp();
                while (need > widthDp && c + span < limit && valueAt(values, c + span).isEmpty()
                        && !(rowNum == selectedRow && index + span == selectedCol)) { widthDp += colDp(index + span); span++; }
            }
            TextView value = cell(text, header); if(!header&&rowNum==selectedRow&&index==selectedCol){value.setBackgroundColor(0xffffefab);selectedCell=value;} line.addView(value, new LinearLayout.LayoutParams(dp(widthDp * zoom), dp(rowDp() * zoom)));
            if (!header && index < sheetCols.get(sheetIndex)) { final int rr = rowNum, cc = index; value.setOnClickListener(v -> fetchCell(rr, cc)); }
            c += span - 1;
        }
        grid.addView(line);
    }
    private TextView cell(String text, boolean header) {
        if (autoFit) {
            TextView t = new TextView(activity); t.setText(text); t.setTextSize(textSp() * zoom); t.setTextColor(header ? 0xff555555 : Color.BLACK);
            t.setGravity(header ? Gravity.CENTER : Gravity.CENTER_VERTICAL); t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(header ? 0xffeeeeee : Color.WHITE); bg.setStroke(Math.max(1, dp(.5f)), 0xffd4d4d4);
            t.setBackground(bg); t.setPadding(dp(4 * zoom), 0, dp(4 * zoom), 0);
            t.setFocusable(true); return t;
        }
        TextView t = new TextView(activity); t.setText(text); t.setTextSize(14 * zoom); t.setGravity(Gravity.CENTER_VERTICAL); t.setPadding(dp(6 * zoom), dp(3 * zoom), dp(6 * zoom), dp(3 * zoom));
        t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END); t.setBackgroundColor(header ? 0xffe8edf3 : Color.WHITE);
        t.setTextColor(Color.BLACK); t.setTypeface(null, header ? Typeface.BOLD : Typeface.NORMAL); t.setBackgroundResource(android.R.drawable.edit_text);
        t.setFocusable(true); return t;
    }
    private void renderEmpty(String text) { if (grid != null) { grid.removeAllViews(); TextView t = new TextView(activity); t.setText(text); t.setPadding(dp(12), dp(12), dp(12), dp(12)); grid.addView(t); } }
    private void showError(String message) { status.setText((persistentWarning.isEmpty()?"":persistentWarning+" ")+"Ошибка: " + (message == null || message.isEmpty() ? "запрос не выполнен" : message)); setRetryVisible(true); }
    private void setControlsEnabled(boolean enabled) { if (toolbar == null) return; for (Button b : toolButtons) b.setEnabled(enabled); if (sheetSpinner != null) sheetSpinner.setEnabled(enabled); if(editButton!=null)editButton.setEnabled(enabled); if(enabled)updateNavigation(sheetRows.get(sheetIndex),sheetCols.get(sheetIndex)); }
    private void updateNavigation(int rows, int cols) { prevRows.setEnabled(!busy && rowStart > 0); prevCols.setEnabled(!busy && colStart > 0); nextRows.setEnabled(!busy && rowStart + PAGE_ROWS < rows); nextCols.setEnabled(!busy && colStart + PAGE_COLS < cols); }
    private void moveRows(int delta) { if(busy||closed)return; int max = Math.max(0, sheetRows.get(sheetIndex) - 1); int next = Math.max(0, Math.min(max, rowStart + delta)); if (next != rowStart) { rowStart = next; requestPage(horizontal.getScrollX(), 0, false); } }
    private void moveCols(int delta) { if(busy||closed)return; int max = Math.max(0, sheetCols.get(sheetIndex) - 1); int next = Math.max(0, Math.min(max, colStart + delta)); if (next != colStart) { colStart = next; requestPage(0, vertical.getScrollY(), false); } }

    private void fetchCell(int row, int col) {
        if (busy || closed) return;selectedRow=row;selectedCol=col;if(cachedPage!=null)renderPage(cachedPage);busy = true; setControlsEnabled(false); status.setText("Загрузка ячейки…"); final long token=++generation;
        worker.execute(() -> { JSONObject result=null; Exception error=null; try { result=backend.call(new JSONObject().put("op","sheet_view_cell").put("session",opened.optString("session")).put("sheet",sheetIndex).put("row",row).put("col",col)); if(result==null||!result.optBoolean("ok",false)) throw new Exception(result==null?"Пустой ответ":result.optString("error","Ошибка чтения ячейки")); } catch(Exception e){error=e;}
            JSONObject ans=result; Exception err=error; activity.runOnUiThread(()->{if(closed||token!=generation)return; busy=false; setControlsEnabled(true); if(err!=null){showError(err.getMessage());return;} String value=ans.optString("text","");
                TextView content=new TextView(activity); content.setText(value); content.setTextIsSelectable(true); content.setPadding(dp(16),dp(12),dp(16),dp(12));
                ScrollView cellScroll=new ScrollView(activity); cellScroll.addView(content);
                AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(colName(col)+ (row+1)+(ans.optBoolean("truncated",false)?" · текст сокращён":"")).setView(cellScroll).setNeutralButton("Копировать",(d,w)->{ClipboardManager cm=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE); cm.setPrimaryClip(ClipData.newPlainText("cell",value));}).setPositiveButton("Закрыть",null).create(); dialog.show(); status.setText(ans.optBoolean("truncated",false)?"Текст ячейки ограничен источником ответа.":"Ячейка открыта только для чтения.");
            });
        });
    }
    private void showGoDialog() {
        EditText input = new EditText(activity); input.setSingleLine(true); input.setHint("Например, A1 или XFD90000");
        new AlertDialog.Builder(activity).setTitle("Перейти к ячейке").setView(input).setNegativeButton("Отмена",null).setPositiveButton("Перейти",(d,w)->{ if(busy||closed)return; int[] rc=parseAddress(input.getText().toString().trim()); if(rc==null||rc[0]>=sheetRows.get(sheetIndex)||rc[1]>=sheetCols.get(sheetIndex)){status.setText("Некорректный адрес или ячейка вне размеров листа.");return;} selectedRow=rc[0];selectedCol=rc[1];rowStart=Math.min(rc[0],Math.max(0,sheetRows.get(sheetIndex)-PAGE_ROWS)); colStart=Math.min(rc[1],Math.max(0,sheetCols.get(sheetIndex)-PAGE_COLS)); requestPage(0, 0, true); }).show();
    }
    private int[] parseAddress(String s) { if(s==null)return null; String u=s.toUpperCase(java.util.Locale.ROOT); int i=0; long col=0; while(i<u.length()&&u.charAt(i)>='A'&&u.charAt(i)<='Z'){col=col*26+(u.charAt(i)-'A'+1);if(col>16384)return null;i++;} if(i==0||i==u.length())return null; long row=0; while(i<u.length()&&Character.isDigit(u.charAt(i))){row=row*10+(u.charAt(i)-'0');if(row>1048576)return null;i++;} if(i!=u.length()||row<1||col<1)return null; return new int[]{(int)row-1,(int)col-1}; }
    private String colName(int zero) { int n=Math.max(0,zero)+1; StringBuilder b=new StringBuilder(); while(n>0){int r=(n-1)%26;b.append((char)('A'+r));n=(n-1)/26;}return b.reverse().toString(); }
    private void showFindDialog() { EditText input=new EditText(activity); input.setSingleLine(true); input.setHint("Текст для поиска"); if(lastQuery!=null)input.setText(lastQuery); new AlertDialog.Builder(activity).setTitle("Найти на листе").setView(input).setNegativeButton("Отмена",null).setPositiveButton("Найти",(d,w)->{lastQuery=input.getText().toString();lastHitRow=lastHitCol=-1; performFind();}).show(); }
    private void findNext() { if(lastQuery==null||lastQuery.isEmpty()){showFindDialog();return;} performFind(); }
    private void performFind() {
        if(busy||closed)return; busy=true; setControlsEnabled(false); status.setText("Поиск…"); final long token=++generation; final int sh=sheetIndex, afterR=lastHitRow, afterC=lastHitCol; final String query=lastQuery;
        worker.execute(()->{JSONObject res=null;Exception err=null;try{res=backend.call(new JSONObject().put("op","sheet_view_find").put("session",opened.optString("session")).put("sheet",sh).put("query",query).put("afterRow",afterR).put("afterCol",afterC));if(res==null||!res.optBoolean("ok",false))throw new Exception(res==null?"Пустой ответ":res.optString("error","Поиск не выполнен"));}catch(Exception e){err=e;} JSONObject ans=res;Exception failure=err;activity.runOnUiThread(()->{if(closed||token!=generation)return;busy=false;setControlsEnabled(true);if(failure!=null){showError(failure.getMessage());return;}if(!ans.optBoolean("found",false)){if(afterR>=0){status.setText("Достигнут конец листа. Нажмите «Далее», чтобы искать с начала.");lastHitRow=lastHitCol=-1;}else status.setText("Совпадений на этом листе нет.");return;}int r=ans.optInt("row",-1),c=ans.optInt("col",-1); if(r<0||c<0){status.setText("Ответ поиска не содержит корректного адреса.");return;} if(lastHitRow>=0&&(r<lastHitRow||(r==lastHitRow&&c<=lastHitCol)))status.setText("Поиск продолжен с начала листа.");else status.setText("Найдено: "+colName(c)+(r+1));lastHitRow=r;lastHitCol=c;selectedRow=r;selectedCol=c;rowStart=Math.min(r,Math.max(0,sheetRows.get(sheetIndex)-PAGE_ROWS));colStart=Math.min(c,Math.max(0,sheetCols.get(sheetIndex)-PAGE_COLS));requestPage(0, 0, true);});});
    }
    /** Releases the worker; the owner remains responsible for closing the native session. */
    public void close() { if (started) hideAppChrome(false); closed=true; generation++; worker.shutdownNow(); }
}
