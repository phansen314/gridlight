<p align="center"><img src="assets/logo/svg/gridlight-lockup-on-light.svg" alt="Gridlight" width="360"></p>

# Gridlight

A Sudoku board viewer and helper: it shows the board clearly, lights up conflicts, and explains
solving steps the way a person would find them (singles, locked candidates, subsets, fish, wings…).

- **Engine:** Kotlin on the JVM: an immutable board with digit planes, human-technique finders that
  emit a step trace, and a difficulty rating.
- **Frontend:** TypeScript (not built yet).
- **Puzzles:** a plain 9×9 grid of givens (`.` for blank), with optional sections for variant
  regions and constraints after it (extension `.gridlight`).

## Layout

- `bench/`: JMH benchmarks and prototypes comparing board representations (see `bench/REPORT.md`).
- `assets/logo/`: logo, icons and favicons (SVG and PNG).

## Status

Early: research is mostly done and the engine is next.

## License

MIT, see [LICENSE](LICENSE).
