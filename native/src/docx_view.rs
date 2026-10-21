//! Rich, bounded DOCX read-only view backed by WordCraft's document model.
use serde_json::{Value, json};
use std::{fs, sync::Arc};
use wordcraft_doc::props::{LineSpacing, VMerge};
use wordcraft_doc::{Block, Document, Paragraph, Table};

const MAX_FILE: u64 = 32 * 1024 * 1024;
const MAX_OUTPUT: usize = 8 * 1024 * 1024;
const MAX_TEXT: usize = 2_000_000;
const MAX_NODES: usize = 50_000;
const MAX_DEPTH: usize = 8;

fn error(s: impl ToString) -> Value {
    json!({"ok":false,"error":s.to_string()})
}
fn section(s: &wordcraft_doc::SectionProps) -> Value {
    json!({"page_width_pt":s.page_w,"page_height_pt":s.page_h,"margin_left_pt":s.margin_left,"margin_right_pt":s.margin_right,"margin_top_pt":s.margin_top,"margin_bottom_pt":s.margin_bottom,"orientation":if s.landscape {"landscape"} else {"portrait"}})
}
fn paragraph(
    p: &Paragraph,
    doc: &Document,
    counters: &mut wordcraft_doc::numbering::Counters,
) -> Value {
    let resolved = doc.styles.resolve_para(&p.props);
    // WordCraft layout substitutes numbering-level indents unless the paragraph overrides them.
    let (indent_left, indent_first) = if let Some(n) = resolved.numbering {
        if let Some(level) = doc.numbering.level(n.num, n.level) {
            let own_left = p.props.indent_left.is_some()
                || p.props.indent_left_chars.is_some_and(|c| c != 0.0);
            let own_first = p.props.indent_first.is_some()
                || p.props.indent_first_chars.is_some_and(|c| c != 0.0);
            (
                if own_left {
                    resolved.indent_left
                } else {
                    level.indent
                },
                if own_first {
                    resolved.indent_first
                } else {
                    -level.hanging
                },
            )
        } else {
            (resolved.indent_left, resolved.indent_first)
        }
    } else {
        (resolved.indent_left, resolved.indent_first)
    };
    let mut runs = Vec::new();
    let mut off = 0usize;
    for r in &p.runs {
        let end = off.saturating_add(r.len).min(p.text.len());
        if !p.text.is_char_boundary(off) || !p.text.is_char_boundary(end) {
            continue;
        }
        let text = &p.text[off..end];
        let style = p.props.style.as_deref();
        let ch = doc.styles.resolve_char(style, &r.props);
        let color = match ch.color {
            wordcraft_doc::TextColor::Rgb(c) => Some(c.hex()),
            _ => None,
        };
        runs.push(json!({"text":text,"bold":ch.bold,"italic":ch.italic,"underline":ch.underline,"size_pt":ch.size,"color":color,"font":ch.font}));
        off = end;
    }
    if p.runs.is_empty() && !p.text.is_empty() {
        let ch = doc
            .styles
            .resolve_char(p.props.style.as_deref(), &Default::default());
        runs.push(json!({"text":p.text,"bold":ch.bold,"italic":ch.italic,"underline":ch.underline,"size_pt":ch.size,"color":null,"font":ch.font}));
    }
    let list = resolved.numbering.and_then(|n| {
        counters
            .next_label(&doc.numbering, n.num, n.level)
            .map(|(label, _)| json!({"label":label,"level":n.level,"num_id":n.num}))
    });
    let (rule, value) = match resolved.line_spacing {
        LineSpacing::Multiple(v) => ("multiple", v),
        LineSpacing::AtLeast(v) => ("atLeast", v),
        LineSpacing::Exactly(v) => ("exactly", v),
    };
    json!({"type":"paragraph","text":p.plain_text(),"runs":runs,"props":{"style":resolved.style,"alignment":resolved.align,"indent_left_pt":indent_left,"indent_right_pt":resolved.indent_right,"indent_first_pt":indent_first,"space_before_pt":resolved.space_before,"space_after_pt":resolved.space_after,"line_spacing":{"rule":rule,"value":value},"page_break_before":resolved.page_break_before,"keep_next":resolved.keep_next},"list":list})
}
fn blocks(
    bs: &[Arc<Block>],
    doc: &Document,
    counters: &mut wordcraft_doc::numbering::Counters,
    depth: usize,
    nodes: &mut usize,
    text: &mut usize,
    plain: &mut String,
) -> Result<Vec<Value>, String> {
    if depth > MAX_DEPTH {
        return Err("DOCX превышает лимит глубины блоков".into());
    }
    let mut out = Vec::new();
    for (index, b) in bs.iter().enumerate() {
        *nodes = nodes.saturating_add(1);
        if *nodes > MAX_NODES {
            return Err("DOCX превышает лимит 50000 узлов".into());
        }
        match b.as_ref() {
            Block::Para(p) => {
                *text = text.saturating_add(p.text.len());
                if *text > MAX_TEXT {
                    return Err("DOCX превышает лимит текста".into());
                }
                *nodes = nodes.saturating_add(p.runs.len());
                if *nodes > MAX_NODES {
                    return Err("DOCX превышает лимит 50000 узлов".into());
                }
                plain.push_str(&p.plain_text());
                plain.push('\n');
                out.push(paragraph(p, doc, counters));
                if let Some(s) = &p.section {
                    if depth == 0 {
                        // A section marker describes the following section, not the one just completed.
                        let next = bs[index + 1..].iter().find_map(|b| match b.as_ref() {
                            Block::Para(next) => next.section.as_deref(),
                            Block::Table(_) => None,
                        });
                        out.push(json!({"type":"section","section":section(next.unwrap_or(&doc.last_section))}));
                    } else {
                        out.push(json!({"type":"section","section":section(s)}));
                    }
                }
            }
            Block::Table(t) => out.push(table(t, doc, counters, depth, nodes, text, plain)?),
        }
    }
    Ok(out)
}
fn table(
    t: &Table,
    doc: &Document,
    counters: &mut wordcraft_doc::numbering::Counters,
    depth: usize,
    nodes: &mut usize,
    text: &mut usize,
    plain: &mut String,
) -> Result<Value, String> {
    let mut rows = Vec::new();
    for (ri, row) in t.rows.iter().enumerate() {
        let mut cells = Vec::new();
        let mut col = 0usize;
        for c in &row.cells {
            *nodes = nodes.saturating_add(1);
            if *nodes > MAX_NODES {
                return Err("DOCX превышает лимит 50000 узлов".into());
            }
            let colspan = c.span();
            let mut rowspan = 1usize;
            if c.props.vmerge == VMerge::Restart {
                for rr in t.rows.iter().skip(ri + 1) {
                    let mut pos = 0usize;
                    let mut found = None;
                    for cc in &rr.cells {
                        if col >= pos && col < pos + cc.span() {
                            found = Some(cc);
                            break;
                        }
                        pos += cc.span();
                    }
                    if found.is_some_and(|x| x.props.vmerge == VMerge::Continue) {
                        rowspan += 1
                    } else {
                        break;
                    }
                }
            }
            let continuation = c.props.vmerge == VMerge::Continue;
            let content = blocks(&c.blocks, doc, counters, depth + 1, nodes, text, plain)?;
            cells
                .push(json!({"column":col,"col_span":colspan,"row_span":rowspan,"merge_continuation":continuation,"blocks":content}));
            col += colspan;
        }
        rows.push(json!({"cells":cells}));
    }
    Ok(json!({"type":"table","column_widths_pt":t.grid,"rows":rows}))
}
pub fn view(path: &str) -> Value {
    let result = (|| -> Result<Value, String> {
        let meta = fs::metadata(path).map_err(|e| e.to_string())?;
        if meta.len() > MAX_FILE {
            return Err("Файл превышает лимит 32 МБ".into());
        }
        let bytes = fs::read(path).map_err(|e| e.to_string())?;
        let doc = wordcraft_docx::read(&bytes).map_err(|e| e.to_string())?;
        let mut nodes = 0;
        let mut text = 0;
        let mut counters = wordcraft_doc::numbering::Counters::default();
        let mut plain_text = String::new();
        let content = blocks(
            &doc.body,
            &doc,
            &mut counters,
            0,
            &mut nodes,
            &mut text,
            &mut plain_text,
        )?;
        let mut legacy = Vec::new();
        for b in &doc.body {
            if let Block::Para(p) = b.as_ref() {
                let first = p.runs.first();
                legacy.push(json!({"text":p.plain_text(),"bold":first.and_then(|r|r.props.bold),"italic":first.and_then(|r|r.props.italic)}));
            }
        }
        let initial_section = doc
            .body
            .iter()
            .find_map(|b| match b.as_ref() {
                Block::Para(p) => p.section.as_deref(),
                Block::Table(_) => None,
            })
            .unwrap_or(&doc.last_section);
        let output = json!({"ok":true,"schema_version":1,"blocks":content,"section":section(initial_section),"paragraphs":legacy,"plain_text":plain_text,"warning":"Совместимый paragraphs содержит только абзацы верхнего уровня с текстом и форматированием первого run; расширенные данные передаются в blocks. Изображения и stories вне body не экспортируются.","nodes":nodes,"text_bytes":text});
        let size = serde_json::to_vec(&output)
            .map_err(|e| e.to_string())?
            .len();
        if size > MAX_OUTPUT {
            return Err("DOCX-просмотр превышает лимит JSON 8 МБ".into());
        }
        Ok(output)
    })();
    result.unwrap_or_else(error)
}
