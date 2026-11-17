<p align="center"><img src="assets/logo/svg/gridlight-lockup-on-light.svg" alt="Gridlight" width="360"></p>

# Gridlight

A Sudoku board viewer and helper: it shows the board clearly, lights up conflicts, and explains
solving steps the way a person would find them (singles, locked candidates, subsets, fish, wings…).

- **Engine:** Kotlin on the JVM: an immutable board (built), then human-technique finders that emit a
  step trace, and a difficulty rating (not built yet).
- **Frontend:** TypeScript (not built yet).
- **Puzzles:** a plain 9×9 grid of givens (`.` for blank), with optional sections for variant
  regions and constraints after it (extension `.gridlight`).

## Layout

- `engine/`: the engine, package `gridlight.engine`: the immutable `Board` and `Transient`, `Geometry` and
  `Region`, the value types and sets, the grid parser and the rule checks (`conflicts`, `isSolved`).
- `bench/`: JMH benchmarks and prototypes comparing board representations (see `bench/REPORT.md`).
- `assets/logo/`: logo, icons and favicons (SVG and PNG).

## Status

Early: the board is built and tested; solving steps, the solver and the frontend come next.

Build and test with `./gradlew build` (JDK 25 toolchain); benchmarks with `./gradlew :bench:jmh`
(see `bench/REPORT.md`).

## License

MIT, see [LICENSE](LICENSE).
