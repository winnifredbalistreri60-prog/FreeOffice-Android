package ru.freeoffice.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Russian-language dialogs for importing and exporting plain text formats. */
public final class PlainFileUi {
    private PlainFileUi() {}

    public interface Loader {
        void run(Callable<JSONObject> work, Consumer<JSONObject> done);
    }

    public static void chooseImport(Activity a, File file, String ext, Loader loader,
                                    Consumer<JSONObject> opened, Runnable cancelled) {
        if (a == null || a.isFinishing() || a.isDestroyed()) { if (cancelled != null) cancelled.run(); return; }
        final boolean tabular = is(ext, "csv") || is(ext, "tsv");
        LinearLayout body = column(a);
        label(a, body, "Кодировка файла");
        Spinner charset = spinner(a, body, new String[]{"Автоопределение", "UTF-8", "Windows-1251", "UTF-16 LE", "UTF-16 BE"});
        Spinner delim = null;
        if (tabular) {
            label(a, body, "Разделитель столбцов");
            delim = spinner(a, body, new String[]{"Автоопределение", "Запятая ,", "Точка с запятой ;", "Табуляция"});
            if (is(ext,"tsv")) delim.setSelection(3);
        }
        final Spinner delimiter = delim;
        TextView note = label(a, body, "Все значения импортируются как текст: например, 00123 останется 00123, а не числом. Значения, начинающиеся с =, также будут текстом, а не формулами.");
        note.setTextSize(14);
        Button preview = new Button(a); preview.setText("Проверить предпросмотр"); body.addView(preview);
        TextView result = label(a, body, "Предпросмотр появится здесь.");
        result.setTextIsSelectable(true); result.setTextSize(14);
        ScrollView scroll = new ScrollView(a); scroll.setFillViewport(true); scroll.addView(body);
        final boolean[] active = {false};
        final boolean[] dismissed = {false};
        final JSONObject[] latest = {null};
        AlertDialog dialog = new AlertDialog.Builder(a).setTitle("Импорт файла")
                .setView(scroll).setNegativeButton("Отмена", (d,w) -> { dismissed[0] = true; if (cancelled != null) cancelled.run(); })
                .setPositiveButton("Открыть", null).create();
        dialog.setOnCancelListener(d -> { dismissed[0] = true; if (cancelled != null) cancelled.run(); });
        preview.setOnClickListener(v -> {
            if (active[0] || dismissed[0]) return;
            active[0] = true; preview.setEnabled(false); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            result.setText("Читаю файл…");
            runImport(loader, file, ext, encoding(charset), delimiter(delimiter), r -> {
                active[0] = false;
                if (!valid(a, dismissed[0])) return;
                preview.setEnabled(true); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                if (r.has("plainError")) { result.setText("Ошибка: " + r.optString("plainError", "Не удалось прочитать файл")); latest[0] = null; return; }
                latest[0] = r;
                result.setText(previewText(r, tabular));
            });
        });
        dialog.setOnShowListener(d -> {
            Button open = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            open.setOnClickListener(v -> {
                if (active[0] || dismissed[0]) return;
                active[0] = true; open.setEnabled(false); preview.setEnabled(false);
                result.setText("Открываю файл…");
                runImport(loader, file, ext, encoding(charset), delimiter(delimiter), r -> {
                    active[0] = false;
                    if (!valid(a, dismissed[0])) return;
                    open.setEnabled(true); preview.setEnabled(true);
                    if (r.has("plainError")) { result.setText("Ошибка: " + r.optString("plainError", "Не удалось прочитать файл")); return; }
                    dismissed[0] = true;
                    if (opened != null) opened.accept(r);
                    dialog.dismiss();
                });
            });
        });
        dialog.show();
    }

    private static void runImport(Loader loader, File file, String ext, String enc, String delim, Consumer<JSONObject> done) {
        Callable<JSONObject> work = () -> {
            try { return PlainFormats.open(file, ext, enc, delim); }
            catch (Exception e) { return new JSONObject().put("ok", true).put("plainError", safeMessage(e)); }
        };
        try { loader.run(work, r -> done.accept(r == null ? failed("Не удалось прочитать файл") : r)); }
        catch (RuntimeException e) { done.accept(failed(safeMessage(e))); }
    }

    public static void chooseExport(Activity a, String format, JSONObject defaults, Consumer<JSONObject> chosen) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        boolean tabular = is(format,"csv") || is(format,"tsv");
        LinearLayout body = column(a);
        label(a, body, "Кодировка");
        Spinner enc = spinner(a, body, new String[]{"UTF-8", "Windows-1251", "UTF-16 LE", "UTF-16 BE"});
        enc.setSelection(indexForEncoding(defaults == null ? "" : defaults.optString("encoding","")));
        label(a, body, "Окончание строк");
        Spinner nl = spinner(a, body, new String[]{"LF (Unix)", "CRLF (Windows)", "CR"});
        String currentNl = defaults == null ? "" : defaults.optString("newline", "");
        nl.setSelection("CRLF".equalsIgnoreCase(currentNl) ? 1 : ("CR".equalsIgnoreCase(currentNl) ? 2 : 0));
        Spinner sep = null;
        String customDelimiter = "";
        if (tabular && is(format,"csv")) {
            label(a, body, "Разделитель");
            String sourceDelimiter = defaults == null ? "" : defaults.optString("delimiter", "");
            if (sourceDelimiter.length() == 1 && sourceDelimiter.charAt(0) != ',' && sourceDelimiter.charAt(0) != ';' && sourceDelimiter.charAt(0) != '\t') customDelimiter = sourceDelimiter;
            sep = spinner(a, body, customDelimiter.isEmpty() ? new String[]{"Запятая ,", "Точка с запятой ;", "Табуляция"} : new String[]{"Запятая ,", "Точка с запятой ;", "Табуляция", "Другой символ: " + displayDelimiter(customDelimiter)});
            String d = defaults == null ? "" : defaults.optString("delimiter", "");
            sep.setSelection(customDelimiter.isEmpty() ? (";".equals(d) ? 1 : ("tab".equalsIgnoreCase(d) || "\\t".equals(d) || "\t".equals(d) ? 2 : 0)) : 3);
        }
        final Spinner separator = sep;
        final String retainedCustomDelimiter = customDelimiter;
        CheckBox bom = new CheckBox(a); bom.setText("Добавить BOM (маркер кодировки)");
        bom.setChecked(defaults != null && defaults.optBoolean("bom", false)); body.addView(bom);
        enc.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                boolean supported = position != 1;
                bom.setEnabled(supported);
                if (!supported) bom.setChecked(false);
            }
        });
        CheckBox neutralize = null;
        if (tabular) {
            TextView warning = label(a, body, "Будет экспортирован только выбранный лист."); warning.setTextSize(14);
            neutralize = new CheckBox(a); neutralize.setText("Защитить от формул в таблицах");
            neutralize.setChecked(false); body.addView(neutralize);
            TextView explanation = label(a, body, "При включении добавляется апостроф перед значениями, начинающимися (после пробелов) с =, +, - или @. Отключите, чтобы сохранить точный текст.");
            explanation.setTextSize(14);
        }
        final CheckBox safe = neutralize;
        ScrollView scroll = new ScrollView(a); scroll.setFillViewport(true); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(a).setTitle("Параметры экспорта")
                .setView(scroll).setNegativeButton("Отмена", null).setPositiveButton("Продолжить", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                JSONObject result = new JSONObject().put("encoding", exportEncoding(enc.getSelectedItemPosition()))
                        .put("newline", nl.getSelectedItemPosition() == 1 ? "CRLF" : (nl.getSelectedItemPosition() == 2 ? "CR" : "LF"))
                        .put("bom", bom.isChecked())
                        .put("delimiter", tabular ? (is(format,"tsv") ? "tab" : (separator.getSelectedItemPosition() == 1 ? ";" : (separator.getSelectedItemPosition() == 2 ? "tab" : (separator.getSelectedItemPosition() == 3 ? retainedCustomDelimiter : ",")))) : "")
                        .put("neutralizeFormulas", tabular && safe != null && safe.isChecked());
                if (chosen != null) chosen.accept(result);
                dialog.dismiss();
            } catch (Exception ignored) { Toast.makeText(a, "Не удалось подготовить параметры", Toast.LENGTH_SHORT).show(); }
        }));
        dialog.show();
    }

    private static String previewText(JSONObject r, boolean tabular) {
        StringBuilder out = new StringBuilder();
        out.append("Кодировка: ").append(r.optString("encoding", "неизвестно"));
        if (r.optBoolean("bom", false)) out.append(" · BOM");
        out.append("\nОкончание строк: ").append(r.optString("newline", "неизвестно"));
        if (tabular) out.append("\nРазделитель: ").append(displayDelimiter(r.optString("delimiter", "неизвестно")));
        String warning = r.optString("warning", "");
        if (!warning.isEmpty() && !"null".equals(warning)) out.append("\nПредупреждение: ").append(warning);
        out.append("\n\n");
        if (!tabular) return out.append(safePrefix(r.optString("text", ""), 1000)).toString();
        JSONArray sheets = r.optJSONArray("sheets"), rows = null;
        if (sheets != null && sheets.length() > 0) rows = sheets.optJSONObject(0) == null ? null : sheets.optJSONObject(0).optJSONArray("rows");
        if (rows == null) return out.append("Нет данных для предпросмотра.").toString();
        for (int i=0; i<Math.min(5, rows.length()); i++) {
            JSONArray cells = rows.optJSONArray(i); if (cells == null) continue;
            int count = Math.min(8, cells.length());
            for (int j=0; j<count; j++) { if (j>0) out.append("  |  "); out.append(safePrefix(cells.optString(j, ""), 180)); }
            if (cells.length()>8) out.append("  |  …");
            out.append('\n');
        }
        return safePrefix(out.toString(), 6000);
    }
    private static String safePrefix(String s, int n) {
        if (s == null || s.length() <= n) return s == null ? "" : s;
        int end=n; if (end>0 && Character.isHighSurrogate(s.charAt(end-1)) && end<s.length() && Character.isLowSurrogate(s.charAt(end))) end--;
        return s.substring(0,end)+"…";
    }
    private static String displayDelimiter(String delimiter) {
        if (delimiter == null || delimiter.length() != 1) return delimiter == null ? "" : delimiter;
        char c = delimiter.charAt(0);
        int type = Character.getType(c);
        if (Character.isWhitespace(c) || Character.isISOControl(c) || type == Character.FORMAT || type == Character.UNASSIGNED || type == Character.PRIVATE_USE || type == Character.SURROGATE) {
            String name = c == ' ' ? "Пробел" : (c == '\t' ? "Табуляция" : (c == '\n' ? "Перевод строки" : (c == '\r' ? "Возврат каретки" : "Символ")));
            return name + String.format(java.util.Locale.ROOT, " (U+%04X)", (int)c);
        }
        return delimiter;
    }
    private static int indexForEncoding(String e) {
        if (e.equalsIgnoreCase("windows-1251") || e.equalsIgnoreCase("cp1251")) return 1;
        if (e.equalsIgnoreCase("UTF-16LE") || e.equalsIgnoreCase("UTF-16 LE")) return 2;
        if (e.equalsIgnoreCase("UTF-16BE") || e.equalsIgnoreCase("UTF-16 BE")) return 3;
        return 0;
    }
    private static String exportEncoding(int i) { return new String[]{"UTF-8","windows-1251","UTF-16LE","UTF-16BE"}[Math.max(0,Math.min(3,i))]; }
    private static String encoding(Spinner s) { return new String[]{"auto","UTF-8","windows-1251","UTF-16LE","UTF-16BE"}[s.getSelectedItemPosition()]; }
    private static String delimiter(Spinner s) { if (s == null) return "auto"; return new String[]{"auto",",",";","tab"}[s.getSelectedItemPosition()]; }
    private static LinearLayout column(Activity a) {
        LinearLayout l = new LinearLayout(a); l.setOrientation(LinearLayout.VERTICAL);
        int p=(int)(16*a.getResources().getDisplayMetrics().density); l.setPadding(p,p,p,p); return l;
    }
    private static TextView label(Activity a, LinearLayout parent, String text) {
        TextView v=new TextView(a); v.setText(text); v.setTextSize(16); v.setPadding(0,dp(a,7),0,dp(a,5)); parent.addView(v,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)); return v;
    }
    private static Spinner spinner(Activity a, LinearLayout parent, String[] values) {
        Spinner s=new Spinner(a); s.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, values)); parent.addView(s); return s;
    }
    private static int dp(Activity a,int n) { return (int)(n*a.getResources().getDisplayMetrics().density+0.5f); }
    private static boolean is(String a,String b) { return a != null && a.trim().equalsIgnoreCase(b); }
    private static boolean valid(Activity a, boolean dismissed) { return !dismissed && a != null && !a.isFinishing() && !a.isDestroyed(); }
    private static JSONObject failed(String message) { try { return new JSONObject().put("ok",true).put("plainError",message); } catch (Exception e) { return new JSONObject(); } }
    private static String safeMessage(Exception e) { String m=e.getMessage(); return m == null || m.isEmpty() ? "Ошибка чтения файла" : m; }
}
