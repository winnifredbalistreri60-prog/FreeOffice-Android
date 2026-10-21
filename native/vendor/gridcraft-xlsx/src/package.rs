//! OPC package access: reading zip parts with decompression limits, relationships and part-name
//! resolution; writing a zip package deterministically.

use std::collections::HashMap;
use std::io::{Cursor, Read, Write};

use crate::IoError;
use crate::xml::{self, El};

/// Largest decompressed part accepted.
pub const MAX_PART: u64 = 32 * 1024 * 1024;
/// Largest total decompressed size read from one package.
pub const MAX_TOTAL: u64 = 64 * 1024 * 1024;
/// Most entries accepted in the zip directory.
pub const MAX_ENTRIES: usize = 4096;

pub struct Package<'a> {
    zip: zip::ZipArchive<Cursor<&'a [u8]>>,
    /// Lower-case normalized part name → zip index.
    names: HashMap<String, usize>,
    total: u64,
}

/// A relationship.
#[derive(Clone, Debug)]
pub struct Rel {
    pub id: String,
    /// Last path segment of the relationship type (`worksheet`, `styles`, …): the same for the
    /// transitional and strict namespaces.
    pub kind: String,
    /// Resolved part name (no leading `/`), or the raw target for external relationships.
    pub target: String,
    pub external: bool,
}

pub fn normalize(name: &str) -> String {
    name.replace('\\', "/").trim_start_matches('/').to_ascii_lowercase()
}

impl<'a> Package<'a> {
    pub fn open(bytes: &'a [u8]) -> Result<Package<'a>, IoError> {
        let mut zip = zip::ZipArchive::new(Cursor::new(bytes)).map_err(|e| IoError::Zip(e.to_string()))?;
        if zip.len() > MAX_ENTRIES {
            return Err(IoError::TooLarge(format!("{} zip entries", zip.len())));
        }
        let mut names = HashMap::new();
        let mut declared_total = 0u64;
        for i in 0..zip.len() {
            let f = zip.by_index(i).map_err(|e| IoError::Zip(e.to_string()))?;
            if f.size() > MAX_PART || f.size() > MAX_TOTAL.saturating_sub(declared_total) {
                return Err(IoError::TooLarge("XLSX: 32 MiB per ZIP entry / 64 MiB expanded package limit".into()));
            }
            declared_total += f.size();
            drop(f);
            if let Some(n) = zip.name_for_index(i) {
                names.entry(normalize(n)).or_insert(i);
            }
        }
        // Check actual output, even optional parts, before the lenient reader can
        // downgrade resource errors to warnings. Never allocate from ZIP metadata.
        let mut actual_total = 0u64;
        let mut scratch = [0u8; 8192];
        for i in 0..zip.len() {
            let mut f = zip.by_index(i).map_err(|e| IoError::Zip(e.to_string()))?;
            let mut actual_part = 0u64;
            loop {
                let remaining = (MAX_PART - actual_part).min(MAX_TOTAL - actual_total);
                let count = scratch.len().min(remaining.saturating_add(1) as usize);
                let n = f.read(&mut scratch[..count]).map_err(|e| IoError::Zip(e.to_string()))?;
                if n == 0 { break; }
                if n as u64 > remaining {
                    return Err(IoError::TooLarge("XLSX decompression exceeds the 32 MiB entry / 64 MiB package limit".into()));
                }
                actual_part += n as u64;
                actual_total += n as u64;
            }
            if actual_part != f.size() {
                return Err(IoError::Zip("ZIP entry expanded size does not match its directory".into()));
            }
        }
        Ok(Package { zip, names, total: 0 })
    }

    pub fn has(&self, part: &str) -> bool {
        self.names.contains_key(&normalize(part))
    }

    /// Reads a part's bytes; `Ok(None)` when it doesn't exist.
    pub fn read(&mut self, part: &str) -> Result<Option<Vec<u8>>, IoError> {
        self.read_limited(part, MAX_PART)
    }

    /// Reads a small metadata part without allowing it to expand to the general part limit.
    pub fn read_limited(&mut self, part: &str, maximum: u64) -> Result<Option<Vec<u8>>, IoError> {
        let maximum = maximum.min(MAX_PART);
        let Some(&idx) = self.names.get(&normalize(part)) else {
            return Ok(None);
        };
        let mut f = self.zip.by_index(idx).map_err(|e| IoError::Zip(format!("{part}: {e}")))?;
        if f.size() > maximum {
            return Err(IoError::TooLarge(format!("{part} expands to {} bytes", f.size())));
        }
        let remaining = MAX_TOTAL.saturating_sub(self.total);
        let limit = maximum.min(remaining);
        if f.size() > limit {
            return Err(IoError::TooLarge("XLSX cumulative expanded-read budget exceeded (64 MiB)".into()));
        }
        let mut out = Vec::new();
        let mut scratch = [0u8; 8192];
        loop {
            let left = limit - out.len() as u64;
            let count = scratch.len().min(left.saturating_add(1) as usize);
            let n = f.read(&mut scratch[..count]).map_err(|e| IoError::Zip(format!("{part}: {e}")))?;
            if n == 0 { break; }
            if n as u64 > left {
                return Err(IoError::TooLarge(format!("{part}: expanded-read budget exceeded")));
            }
            out.try_reserve_exact(n).map_err(|_| IoError::TooLarge("Insufficient memory for bounded XLSX part".into()))?;
            out.extend_from_slice(&scratch[..n]);
        }
        self.total += out.len() as u64;
        Ok(Some(out))
    }

    pub fn read_xml(&mut self, part: &str) -> Result<Option<El>, IoError> {
        match self.read(part)? {
            Some(b) => xml::parse(&b).map(Some).map_err(|e| prefix(part, e)),
            None => Ok(None),
        }
    }

    /// Relationships of a part (`xl/workbook.xml` → `xl/_rels/workbook.xml.rels`). Missing or
    /// broken rels parts give an empty list.
    pub fn rels(&mut self, part: &str) -> Result<Vec<Rel>, IoError> {
        let (dir, file) = split_dir(part);
        let rels_name = if dir.is_empty() { format!("_rels/{file}.rels") } else { format!("{dir}/_rels/{file}.rels") };
        let root = match self.read(&rels_name)? {
            Some(b) => match xml::parse(&b) {
                Ok(r) => r,
                Err(_) => return Ok(vec![]),
            },
            None => return Ok(vec![]),
        };
        Ok(root
            .kids("Relationship")
            .filter_map(|r| {
                let id = r.attr("Id")?.to_string();
                let ty = r.attr("Type").unwrap_or("");
                let kind = ty.rsplit('/').next().unwrap_or("").to_string();
                let target_raw = r.attr("Target")?;
                let external = r.attr("TargetMode").is_some_and(|m| m.eq_ignore_ascii_case("External"));
                let target = if external { target_raw.to_string() } else { resolve(dir, target_raw) };
                Some(Rel { id, kind, target, external })
            })
            .collect())
    }
}

fn prefix(part: &str, e: IoError) -> IoError {
    match e {
        IoError::Xml(m) => IoError::Xml(format!("{part}: {m}")),
        o => o,
    }
}

/// `xl/worksheets/sheet1.xml` → (`xl/worksheets`, `sheet1.xml`).
pub fn split_dir(part: &str) -> (&str, &str) {
    let p = part.trim_start_matches('/');
    match p.rfind('/') {
        Some(i) => (p.get(..i).unwrap_or(""), p.get(i + 1..).unwrap_or("")),
        None => ("", p),
    }
}

/// Resolves a relationship target against the source part's directory.
pub fn resolve(dir: &str, target: &str) -> String {
    let target = target.replace('\\', "/");
    let target = target.split('#').next().unwrap_or("").to_string();
    let mut segs: Vec<&str> = if target.starts_with('/') { vec![] } else { dir.split('/').filter(|s| !s.is_empty()).collect() };
    for s in target.split('/') {
        match s {
            "" | "." => {}
            ".." => {
                segs.pop();
            }
            s => segs.push(s),
        }
    }
    segs.join("/")
}

// ---------------------------------------------------------------- writing

pub struct ZipOut {
    zip: zip::ZipWriter<Cursor<Vec<u8>>>,
}

impl ZipOut {
    pub fn new() -> ZipOut {
        ZipOut { zip: zip::ZipWriter::new(Cursor::new(Vec::new())) }
    }
    pub fn add(&mut self, name: &str, data: &[u8]) -> Result<(), IoError> {
        let opts = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated)
            .compression_level(Some(6))
            .last_modified_time(zip::DateTime::default())
            .large_file(data.len() as u64 >= u32::MAX as u64);
        self.zip.start_file(name, opts).map_err(|e| IoError::Zip(e.to_string()))?;
        self.zip.write_all(data).map_err(|e| IoError::Zip(e.to_string()))?;
        Ok(())
    }
    pub fn finish(self) -> Result<Vec<u8>, IoError> {
        self.zip.finish().map(|c| c.into_inner()).map_err(|e| IoError::Zip(e.to_string()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolving() {
        assert_eq!(resolve("xl", "worksheets/sheet1.xml"), "xl/worksheets/sheet1.xml");
        assert_eq!(resolve("xl/worksheets", "../drawings/d1.xml"), "xl/drawings/d1.xml");
        assert_eq!(resolve("xl/worksheets", "/xl/tables/t.xml"), "xl/tables/t.xml");
        assert_eq!(resolve("", "xl/workbook.xml"), "xl/workbook.xml");
        assert_eq!(resolve("a", "../../../x"), "x");
        assert_eq!(split_dir("xl/workbook.xml"), ("xl", "workbook.xml"));
        assert_eq!(split_dir("x.xml"), ("", "x.xml"));
    }
}
