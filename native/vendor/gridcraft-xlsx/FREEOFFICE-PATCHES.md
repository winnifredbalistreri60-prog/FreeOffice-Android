# FreeOffice Android ZIP safety patch

Based on GridCraft `c30fa3f1cf324603f3bfc0171689b1d4083b40e5`, crate `gridcraft-xlsx` 0.8.0. Upstream files outside the FreeOffice project are not modified. Original MIT / Apache-2.0 licenses and NOTICE are retained alongside the source.

The Android dependency uses this app-local crate rather than the shared upstream XLSX crate. Its standalone Cargo manifest keeps the existing upstream core/formula/model dependencies.

## Deliberate limits

- At most 4,096 ZIP directory entries.
- At most 32 MiB expanded bytes per entry.
- At most 64 MiB expanded bytes across all entries, including entries not used by the workbook parser.
- The package reader also keeps a 64 MiB cumulative expanded-read budget; repeated reads count again.

`Package::open` validates directory sizes, then checks actual decompressed output and CRCs using an 8 KiB scratch buffer before workbook parsing. This prevents optional-part error recovery from hiding oversized archive entries. Reading each selected part checks its remaining budget before heap allocation and checks each chunk before adding bytes. Allocation failures in the part buffer use `try_reserve_exact` and return an error.

Preflight reads entries once, and normal parsing reads selected entries again. This costs additional CPU and intentionally rejects some large but valid workbooks. The limits bound ZIP expansion, not every allocation in the complete spreadsheet model or XML parser. They are not a general promise that any workbook below the limits can fit in memory.

Only `src/package.rs`, the module-level limit description in `src/lib.rs`, and the standalone dependency manifest differ from upstream. No functional tests are run by the build implementer; app validation belongs to the designated DroidDeck Tester.
