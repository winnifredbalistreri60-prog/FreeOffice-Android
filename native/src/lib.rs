use gridcraft_core::{CellRef, Value};
use gridcraft_model::{Cell, Formula, Sheet, Workbook};
use serde::{Deserialize, Serialize};
use std::{
    ffi::c_void,
    fs,
    panic::{AssertUnwindSafe, catch_unwind},
    path::Path,
    ptr,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicU64, Ordering},
    },
};
use wordcraft_doc::{Block, CharProps, Document as WordDoc, Paragraph};
mod docx_view;
const MAX_FILE: u64 = 32 * 1024 * 1024;
const MAX_JSON: usize = 5 * 1024 * 1024;
const MAX_OUTPUT: usize = 8 * 1024 * 1024;
const MAX_PARAS: usize = 10_000;
const MAX_TEXT: usize = 100_000;
const MAX_SHEETS: usize = 256;
const MAX_ROWS: usize = 2000;
const MAX_COLS: usize = 200;
const MAX_CELLS: usize = 100_000;
const VIEW_MAX_CELLS: usize = 2_000_000;
struct ViewerCache {
    session: u64,
    workbook: Workbook,
}
static VIEWER: OnceLock<Mutex<Option<ViewerCache>>> = OnceLock::new();
static VIEWER_GENERATION: AtomicU64 = AtomicU64::new(0);
#[derive(Deserialize)]
struct Request {
    op: String,
    path: String,
    #[serde(default)]
    paragraphs: Vec<ParaIn>,
    #[serde(default)]
    sheets: Vec<SheetIn>,
    #[serde(default)]
    literal_strings: bool,
}
#[derive(Deserialize, Serialize, Clone)]
struct ParaIn {
    #[serde(default)]
    text: String,
    #[serde(default)]
    bold: bool,
    #[serde(default)]
    italic: bool,
}
#[derive(Deserialize)]
struct SheetIn {
    #[serde(default)]
    name: String,
    #[serde(default)]
    rows: Vec<Vec<String>>,
}
#[derive(Serialize)]
struct ParaOut {
    text: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    bold: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    italic: Option<bool>,
}
fn err(s: impl ToString) -> serde_json::Value {
    serde_json::json!({"ok":false,"error":s.to_string()})
}
fn read_file(p: &str) -> Result<Vec<u8>, String> {
    let m = fs::metadata(p).map_err(|e| e.to_string())?;
    if m.len() > MAX_FILE {
        return Err("Файл превышает лимит 32 МБ".into());
    }
    fs::read(p).map_err(|e| e.to_string())
}
fn process(v: serde_json::Value) -> serde_json::Value {
    if let Some(op) = v.get("op").and_then(|x| x.as_str()) {
        match op {
            "docx_view" => return docx_view::view(v.get("path").and_then(|x| x.as_str()).unwrap_or("")),
            "pdf_text" | "pdf_search" => return pdf_text(&v),
            "sheet_view_open" => return sheet_view_open(&v),
            "sheet_view_page" => return sheet_view_page(&v),
            "sheet_view_cell" => return sheet_view_cell(&v),
            "sheet_view_find" => return sheet_view_find(&v),
            "sheet_view_close" => return sheet_view_close(&v),
            _ => {}
        }
    }
    let r: Request = match serde_json::from_value(v) {
        Ok(x) => x,
        Err(e) => return err(e),
    };
    match r.op.as_str() {
        "docx_open" => {
            let b = match read_file(&r.path) {
                Ok(x) => x,
                Err(e) => return err(e),
            };
            let d = match wordcraft_docx::read(&b) {
                Ok(x) => x,
                Err(e) => return err(e),
            };
            let mut ps = Vec::new();
            for block in &d.body {
                if let Block::Para(p) = block.as_ref() {
                    let text = p.plain_text();
                    if ps.len() >= MAX_PARAS || text.len() > MAX_TEXT {
                        return err("DOCX превышает ограничение упрощённого импорта");
                    };
                    let run = p.runs.first();
                    ps.push(ParaOut {
                        text,
                        bold: run.and_then(|x| x.props.bold),
                        italic: run.and_then(|x| x.props.italic),
                    });
                }
            }
            serde_json::json!({"ok":true,"paragraphs":ps,"warning":"Импорт передаёт текст и агрегированное bold/italic абзаца; таблицы, сложное форматирование и объекты не передаются упрощённому UI."})
        }
        "docx_save" => {
            if r.paragraphs.len() > MAX_PARAS
                || r.paragraphs.iter().any(|p| p.text.len() > MAX_TEXT)
            {
                return err("Превышен лимит абзацев или текста");
            };
            let mut d = WordDoc::default();
            d.body.clear();
            for x in &r.paragraphs {
                let mut props = CharProps::default();
                props.bold = Some(x.bold);
                props.italic = Some(x.italic);
                d.body
                    .push(Arc::new(Block::Para(Paragraph::with_text(&x.text, props))));
            }
            match wordcraft_docx::write(&d) {
                Ok(b) => match fs::write(&r.path, b) {
                    Ok(_) => {
                        serde_json::json!({"ok":true,"warning":"Создан простой DOCX: только текст и bold/italic абзаца."})
                    }
                    Err(e) => err(e),
                },
                Err(e) => err(e),
            }
        }
        "sheet_open" => {
            let b = match read_file(&r.path) {
                Ok(x) => x,
                Err(e) => return err(e),
            };
            let ext = Path::new(&r.path)
                .extension()
                .and_then(|e| e.to_str())
                .unwrap_or("")
                .to_ascii_lowercase();
            let (wb, warnings) = if ext == "xls" {
                match gridcraft_xlsx::read_xls(&b) {
                    Ok(x) => x,
                    Err(e) => return err(e),
                }
            } else {
                match gridcraft_xlsx::read_xlsx(&b) {
                    Ok(x) => x,
                    Err(e) => return err(e),
                }
            };
            if wb.sheets.len() > MAX_SHEETS {
                return err("Более 256 листов; UI экспорт остановлен без усечения данных");
            };
            let mut out = Vec::new();
            let mut budget = 0usize;
            for sh in &wb.sheets {
                let mut rows: Vec<Vec<String>> = Vec::new();
                if let Some(u) = sh.cells.used_range() {
                    let nr = u.end.row as usize + 1;
                    let nc = u.end.col as usize + 1;
                    if nr > MAX_ROWS || nc > MAX_COLS || nr.saturating_mul(nc) > MAX_CELLS {
                        return err(format!(
                            "Лист «{}» больше лимита UI 2000×200; данные не усечены",
                            sh.name
                        ));
                    };
                    budget = budget.saturating_add(nr.saturating_mul(nc));
                    if budget > MAX_CELLS {
                        return err("Общий бюджет 100000 ячеек превышен; данные не усечены");
                    };
                    for ri in 0..nr {
                        let mut row = Vec::with_capacity(nc);
                        for ci in 0..nc {
                            row.push(
                                sh.cell(CellRef::new(ri as u32, ci as u32))
                                    .map(|c| c.input_text())
                                    .unwrap_or_default(),
                            )
                        }
                        rows.push(row)
                    }
                }
                out.push(serde_json::json!({"name":sh.name,"rows":rows}));
            }
            let base = if ext == "xls" {
                "Импортирован legacy XLS BIFF8; экспорт выполняется только в XLSX."
            } else {
                "GridCraft XLSX; доступен текст ячеек/формулы."
            };
            serde_json::json!({"ok":true,"sheets":out,"warning":if warnings.warnings.is_empty(){base.to_string()}else{format!("{base} {}",warnings.warnings.join("; "))}})
        }
        "sheet_save" => {
            if r.sheets.len() > MAX_SHEETS {
                return err("Максимум 256 листов");
            };
            let mut budget = 0usize;
            for sh in &r.sheets {
                if sh.rows.len() > MAX_ROWS || sh.rows.iter().any(|row| row.len() > MAX_COLS) {
                    return err("Лист превышает лимит 2000×200");
                };
                budget = budget.saturating_add(sh.rows.iter().map(Vec::len).sum::<usize>());
                if budget > MAX_CELLS {
                    return err("Превышен общий лимит 100000 ячеек");
                };
                if sh.name.len() > 255 || sh.rows.iter().flatten().any(|s| s.len() > MAX_TEXT) {
                    return err("Имя листа/ячейка превышает допустимый размер");
                }
            }
            let mut wb = Workbook::new();
            wb.sheets.clear();
            for input in &r.sheets {
                let mut s = Sheet::new(if input.name.is_empty() {
                    "Sheet"
                } else {
                    &input.name
                });
                for (ri, row) in input.rows.iter().enumerate() {
                    for (ci, t) in row.iter().enumerate() {
                        if t.is_empty() {
                            continue;
                        }
                        let c = if r.literal_strings {
                            Cell::value(Value::from(t.clone()))
                        } else if let Some(f) = t.strip_prefix('=') {
                            Cell::formula(Formula::new(f))
                        } else if t.eq_ignore_ascii_case("true") {
                            Cell::value(Value::Bool(true))
                        } else if t.eq_ignore_ascii_case("false") {
                            Cell::value(Value::Bool(false))
                        } else if plain_number(t) {
                            match t.parse::<f64>() {
                                Ok(n) if n.is_finite() => Cell::value(Value::Number(n)),
                                _ => Cell::value(Value::from(t.clone())),
                            }
                        } else {
                            Cell::value(Value::from(t.clone()))
                        };
                        s.set_cell(CellRef::new(ri as u32, ci as u32), c)
                    }
                }
                wb.sheets.push(Arc::new(s))
            }
            if wb.sheets.is_empty() {
                wb.sheets.push(Arc::new(Sheet::new("Sheet1")))
            }
            match gridcraft_xlsx::write_xlsx(&wb) {
                Ok(b) => match fs::write(&r.path, b) {
                    Ok(_) => {
                        serde_json::json!({"ok":true,"warning":if r.literal_strings {"XLSX записан: все импортированные CSV/TSV-значения сохранены строками."} else {"XLSX записан; числа/boolean типизированы, =... сохранено как формула без пересчёта."}})
                    }
                    Err(e) => err(e),
                },
                Err(e) => err(e),
            }
        }
        "pdf_info" => {
            let b = match read_file(&r.path) {
                Ok(x) => x,
                Err(e) => return err(e),
            };
            match pdfcraft_cos::Document::open(Arc::new(b)) {
                Ok(d) => {
                    let pages = pdf_pages(&d);
                    let info = d.trailer().get(b"Info").map(|o| d.resolve(o));
                    let meta = |key: &[u8]| {
                        info.as_ref()
                            .and_then(|o| o.as_dict())
                            .and_then(|x| x.get(key))
                            .and_then(|o| o.as_string())
                            .map(|s| s.to_text())
                    };
                    serde_json::json!({"ok":true,"pages":pages,"title":meta(b"Title"),"author":meta(b"Author")})
                }
                Err(e) => err(e),
            }
        }
        _ => err("unsupported operation"),
    }
}
/// Project WordCraft blocks into the Java DOCX viewer contract with strict recursive limits.
fn bounded_view(value: serde_json::Value) -> serde_json::Value {
    match serde_json::to_vec(&value) {
        Ok(bytes) if bytes.len() <= MAX_OUTPUT => value,
        Ok(_) => err("Structured viewer JSON exceeds 8 MiB output limit"),
        Err(e) => err(e),
    }
}
fn pdf_text(v: &serde_json::Value) -> serde_json::Value {
    let Some(path) = v.get("path").and_then(|x| x.as_str()) else {
        return err("Missing path");
    };
    let bytes = match read_file(path) {
        Ok(b) => b,
        Err(e) => return err(e),
    };
    let doc = match pdfcraft_cos::Document::open(Arc::new(bytes)) {
        Ok(d) => d,
        Err(e) => return err(e),
    };
    let count = pdf_pages(&doc);
    let query = v.get("query").and_then(|x| x.as_str()).unwrap_or("");
    if query.len() > 4096 {
        return err("Search query exceeds 4096 bytes");
    }
    let single = v.get("page").and_then(|x| x.as_u64()).map(|p| p as usize);
    if single.is_some_and(|p| p >= count) {
        return err("Page index out of range (zero-based)");
    }
    if single.is_none() && count > 100 {
        return err("Document has over 100 pages; request a page at a time");
    }
    let indices: Vec<usize> = single
        .map(|p| vec![p])
        .unwrap_or_else(|| (0..count).collect());
    let mut pages = Vec::new();
    let mut budget = 0usize;
    for page in indices {
        let lines = match pdfcraft_edit::text_lines(&doc, page) {
            Ok(x) => x,
            Err(e) => return err(e),
        };
        let selected: Vec<_> = lines
            .into_iter()
            .filter(|l| query.is_empty() || l.text.to_lowercase().contains(&query.to_lowercase()))
            .collect();
        let lines_json: Vec<_> = selected.iter().map(|l| serde_json::json!({"text":l.text,"rect":l.rect,"ink":l.ink,"font":l.base_font,"size":l.size,"bold":l.bold,"italic":l.italic,"color":l.color})).collect();
        let text = selected
            .iter()
            .map(|l| l.text.as_str())
            .collect::<Vec<_>>()
            .join("\n");
        budget = budget.saturating_add(text.len());
        if budget > MAX_OUTPUT {
            return err(
                "PDF text output exceeds 8 MiB; request a single page or use a narrower query",
            );
        }
        pages.push(serde_json::json!({"page":page,"text":text,"lines":lines_json,"coordinatesAvailable":true}));
    }
    bounded_view(serde_json::json!({"ok":true,"available":true,"coordinatesAvailable":true,"pages":pages}))
}
fn viewer_mutex() -> &'static Mutex<Option<ViewerCache>> {
    VIEWER.get_or_init(|| Mutex::new(None))
}
fn lock_viewer() -> std::sync::MutexGuard<'static, Option<ViewerCache>> {
    viewer_mutex().lock().unwrap_or_else(|e| e.into_inner())
}
fn viewer_session(v: &serde_json::Value) -> Result<u64, serde_json::Value> {
    let token = v
        .get("session")
        .and_then(|x| x.as_str())
        .ok_or_else(|| err("Сеанс просмотра устарел или закрыт"))?;
    let n = token
        .parse::<u64>()
        .map_err(|_| err("Сеанс просмотра устарел или закрыт"))?;
    if lock_viewer().as_ref().is_some_and(|c| c.session == n) {
        Ok(n)
    } else {
        Err(err("Сеанс просмотра устарел или закрыт"))
    }
}
fn sheet_view_open(v: &serde_json::Value) -> serde_json::Value {
    let Some(path) = v.get("path").and_then(|x| x.as_str()) else {
        return err("Не указан путь к файлу");
    };
    let b = match read_file(path) {
        Ok(x) => x,
        Err(e) => return err(e),
    };
    let ext = Path::new(path)
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();
    let (wb, warnings) = if ext == "xls" {
        match gridcraft_xlsx::read_xls(&b) {
            Ok(x) => x,
            Err(e) => return err(e),
        }
    } else {
        match gridcraft_xlsx::read_xlsx(&b) {
            Ok(x) => x,
            Err(e) => return err(e),
        }
    };
    if wb.sheets.len() > MAX_SHEETS {
        return err("Более 256 листов; просмотр остановлен");
    }
    if wb.sheets.iter().map(|s| s.cells.len()).sum::<usize>() > VIEW_MAX_CELLS {
        return err("Слишком много заполненных ячеек для безопасного просмотра (лимит 2000000)");
    }
    let sheets: Vec<_> = wb
        .sheets
        .iter()
        .map(|s| {
            let (rows, cols) = s
                .cells
                .used_range()
                .map(|r| (r.end.row as u64 + 1, r.end.col as u64 + 1))
                .unwrap_or((1, 1));
            serde_json::json!({"name":s.name,"rows":rows,"cols":cols})
        })
        .collect();
    let warning = if warnings.warnings.is_empty() {
        if ext == "xls" {
            "Импортирован legacy XLS; просмотр только для чтения.".to_string()
        } else {
            "Просмотр только для чтения; формулы не вычисляются, если нет сохранённого результата."
                .to_string()
        }
    } else {
        warnings.warnings.join("; ")
    };
    let session = VIEWER_GENERATION.fetch_add(1, Ordering::SeqCst) + 1;
    *lock_viewer() = Some(ViewerCache {
        session,
        workbook: wb,
    });
    serde_json::json!({"ok":true,"session":session.to_string(),"sheets":sheets,"warning":warning})
}
fn idx(v: &serde_json::Value, key: &str, default: u64) -> Result<u64, serde_json::Value> {
    match v.get(key) {
        None => Ok(default),
        Some(x) => x
            .as_u64()
            .ok_or_else(|| err(format!("Некорректное поле {key}"))),
    }
}
fn viewer_value(sh: &Sheet, row: u32, col: u32) -> String {
    match sh.cell(CellRef::new(row, col)) {
        Some(c) if c.formula.is_some() && c.value.is_empty() => c.input_text(),
        Some(c) if c.formula.is_some() => c.value.display(),
        Some(c) => c.input_text(),
        None => String::new(),
    }
}
fn sheet_view_page(v: &serde_json::Value) -> serde_json::Value {
    if let Err(e) = viewer_session(v) {
        return e;
    }
    let sheet = match idx(v, "sheet", 0) {
        Ok(x) => x as usize,
        Err(e) => return e,
    };
    let row = match idx(v, "row", 0) {
        Ok(x) => x,
        Err(e) => return e,
    };
    let col = match idx(v, "col", 0) {
        Ok(x) => x,
        Err(e) => return e,
    };
    let nr = match idx(v, "rows", 40) {
        Ok(x) => x,
        Err(e) => return e,
    };
    let nc = match idx(v, "cols", 10) {
        Ok(x) => x,
        Err(e) => return e,
    };
    if nr == 0 || nr > 60 || nc == 0 || nc > 16 || row >= 1_048_576 || col >= 16_384 {
        return err("Окно должно быть до 60×16 в пределах листа");
    }
    let token = v
        .get("session")
        .and_then(|x| x.as_str())
        .and_then(|x| x.parse::<u64>().ok());
    let cache = lock_viewer();
    let Some(c) = cache.as_ref().filter(|c| Some(c.session) == token) else {
        return err("Сеанс просмотра устарел или закрыт");
    };
    let Some(sh) = c.workbook.sheets.get(sheet) else {
        return err("Лист не найден");
    };
    let (tr, tc) = sh
        .cells
        .used_range()
        .map(|r| (r.end.row as u64 + 1, r.end.col as u64 + 1))
        .unwrap_or((1, 1));
    let row = row.min(tr - 1);
    let col = col.min(tc - 1);
    let nr = nr.min(tr - row);
    let nc = nc.min(tc - col);
    let mut truncated = false;
    let rows: Vec<Vec<String>> = (row..row + nr)
        .map(|ri| {
            (col..col + nc)
                .map(|ci| {
                    let s = viewer_value(sh, ri as u32, ci as u32);
                    if s.len() > 1024 {
                        truncated = true;
                        let mut e = 1024;
                        while !s.is_char_boundary(e) {
                            e -= 1
                        }
                        s[..e].to_string()
                    } else {
                        s
                    }
                })
                .collect()
        })
        .collect();
    serde_json::json!({"ok":true,"row":row,"col":col,"rows":rows,"totalRows":tr,"totalCols":tc,"truncated":truncated,"warning":if truncated{"Длинный текст в сетке сокращён; откройте ячейку касанием для чтения."}else{""}})
}
fn sheet_view_cell(v: &serde_json::Value) -> serde_json::Value {
    let token = v
        .get("session")
        .and_then(|x| x.as_str())
        .and_then(|x| x.parse::<u64>().ok());
    let sheet = match idx(v, "sheet", 0) {
        Ok(x) => x as usize,
        Err(e) => return e,
    };
    let row = match idx(v, "row", 0) {
        Ok(x) => x,
        Err(e) => return e,
    };
    let col = match idx(v, "col", 0) {
        Ok(x) => x,
        Err(e) => return e,
    };
    if row >= 1_048_576 || col >= 16_384 {
        return err("Адрес ячейки вне листа");
    }
    let cache = lock_viewer();
    let Some(sh) = cache
        .as_ref()
        .filter(|c| Some(c.session) == token)
        .and_then(|c| c.workbook.sheets.get(sheet))
    else {
        return err("Сеанс просмотра устарел или закрыт");
    };
    let text = viewer_value(sh, row as u32, col as u32);
    let mut out = String::new();
    let mut truncated = false;
    for (i, ch) in text.chars().enumerate() {
        if i >= 100_000 {
            truncated = true;
            break;
        }
        out.push(ch)
    }
    serde_json::json!({"ok":true,"row":row,"col":col,"text":out,"truncated":truncated,"warning":if truncated{"Показаны первые 100000 символов; содержимое усечено."}else{"Для формулы показан сохранённый результат, если он есть; автоматический пересчёт не выполняется."}})
}
fn sheet_view_find(v: &serde_json::Value) -> serde_json::Value {
    let token = v
        .get("session")
        .and_then(|x| x.as_str())
        .and_then(|x| x.parse::<u64>().ok());
    let sheet = match idx(v, "sheet", 0) {
        Ok(x) => x as usize,
        Err(e) => return e,
    };
    let Some(q) = v.get("query").and_then(|x| x.as_str()) else {
        return err("Не задан текст поиска");
    };
    if q.is_empty() || q.chars().count() > 256 {
        return err("Поиск требует непустую строку до 256 символов");
    }
    let ar = match v.get("afterRow") {
        Some(x) => match x.as_i64() {
            Some(n) => n,
            None => return err("Некорректное поле afterRow"),
        },
        None => -1,
    };
    let ac = match v.get("afterCol") {
        Some(x) => match x.as_i64() {
            Some(n) => n,
            None => return err("Некорректное поле afterCol"),
        },
        None => -1,
    };
    let cache = lock_viewer();
    let Some(sh) = cache
        .as_ref()
        .filter(|c| Some(c.session) == token)
        .and_then(|c| c.workbook.sheets.get(sheet))
    else {
        return err("Сеанс просмотра устарел или закрыт");
    };
    let q = q.to_lowercase();
    let found = sh
        .cells
        .iter()
        .filter_map(|(pos, c)| {
            if (pos.row as i64, pos.col as i64) <= (ar, ac) {
                return None;
            }
            let t = if c.formula.is_some() && c.value.is_empty() {
                c.input_text()
            } else if c.formula.is_some() {
                c.value.display()
            } else {
                c.input_text()
            };
            t.to_lowercase().contains(&q).then_some(pos)
        })
        .min_by_key(|p| (p.row, p.col));
    if let Some(pos) = found {
        serde_json::json!({"ok":true,"found":true,"row":pos.row,"col":pos.col})
    } else {
        serde_json::json!({"ok":true,"found":false})
    }
}
fn sheet_view_close(v: &serde_json::Value) -> serde_json::Value {
    let Ok(session) = viewer_session(v) else {
        return err("Сеанс просмотра устарел или закрыт");
    };
    let mut cache = lock_viewer();
    if cache.as_ref().is_some_and(|c| c.session == session) {
        *cache = None;
        serde_json::json!({"ok":true})
    } else {
        err("Сеанс просмотра устарел или закрыт")
    }
}

fn plain_number(s: &str) -> bool {
    if s.is_empty() || s.trim() != s {
        return false;
    }
    let t = s.strip_prefix(['+', '-']).unwrap_or(s);
    if t.is_empty()
        || t.starts_with('.')
        || t.split(['.', 'e', 'E']).next().unwrap_or("").len() > 1 && t.starts_with('0')
    {
        return false;
    }
    s.parse::<f64>().is_ok()
}
fn pdf_pages(d: &pdfcraft_cos::Document) -> usize {
    use pdfcraft_cos::Object;
    let Some(root) = d.root() else { return 0 };
    let catalog = d.get(root);
    let Some(dict) = catalog.as_dict() else {
        return 0;
    };
    let Some(p) = dict.get(b"Pages") else {
        return 0;
    };
    let page = d.resolve(p);
    page.as_dict()
        .and_then(|x| x.get(b"Count"))
        .and_then(Object::as_int)
        .unwrap_or(0)
        .max(0) as usize
}
// JNI 1.6 indexes: NewString 163, GetStringLength 164, GetStringChars 165, ReleaseStringChars 166.
unsafe fn jni_fn<T: Copy>(env: *mut *const c_void, index: usize) -> T {
    unsafe {
        let table = *env.cast::<*const *const c_void>();
        std::mem::transmute_copy(&*table.add(index))
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_ru_freeoffice_app_NativeBridge_call(
    env: *mut *const c_void,
    _class: *mut c_void,
    request: *mut c_void,
) -> *mut c_void {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let len: unsafe extern "system" fn(*mut *const c_void, *mut c_void) -> i32 =
            jni_fn(env, 164);
        let get: unsafe extern "system" fn(*mut *const c_void, *mut c_void, *mut u8) -> *const u16 =
            jni_fn(env, 165);
        let release: unsafe extern "system" fn(*mut *const c_void, *mut c_void, *const u16) =
            jni_fn(env, 166);
        let n = len(env, request).max(0) as usize;
        if n > MAX_JSON {
            return ptr::null_mut();
        }
        let p = get(env, request, ptr::null_mut());
        if p.is_null() {
            return ptr::null_mut();
        }
        let input = String::from_utf16_lossy(std::slice::from_raw_parts(p, n));
        release(env, request, p);
        let v = serde_json::from_str(&input)
            .unwrap_or_else(|e| serde_json::json!({"bad_json":e.to_string()}));
        let text = serde_json::to_string(&process(v))
            .unwrap_or_else(|_| "{\"ok\":false,\"error\":\"json error\"}".into());
        if text.len() > MAX_OUTPUT {
            return ptr::null_mut();
        }
        let utf16: Vec<u16> = text.encode_utf16().collect();
        let new: unsafe extern "system" fn(*mut *const c_void, *const u16, i32) -> *mut c_void =
            jni_fn(env, 163);
        new(env, utf16.as_ptr(), utf16.len() as i32)
    }))
    .unwrap_or(ptr::null_mut())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn docx_roundtrip_for_tester() {
        let p = std::env::temp_dir().join(format!("freeoffice-{}.docx", std::process::id()));
        let a = process(
            serde_json::json!({"op":"docx_save","path":p,"paragraphs":[{"text":"Текст","bold":true}]}),
        );
        assert_eq!(a["ok"], true);
        let b = process(serde_json::json!({"op":"docx_open","path":p}));
        assert_eq!(b["paragraphs"][0]["text"], "Текст");
        let _ = fs::remove_file(p);
    }
    #[test]
    fn xlsx_formula_roundtrip_for_tester() {
        let p = std::env::temp_dir().join(format!("freeoffice-{}.xlsx", std::process::id()));
        let a = process(
            serde_json::json!({"op":"sheet_save","path":p,"sheets":[{"name":"Data","rows":[["2","=1+1"]]}]}),
        );
        assert_eq!(a["ok"], true);
        let b = process(serde_json::json!({"op":"sheet_open","path":p}));
        assert_eq!(b["sheets"][0]["rows"][0][1], "=1+1");
        let _ = fs::remove_file(p);
    }
}
