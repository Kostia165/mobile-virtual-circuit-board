# Circuit Board (Android)

A minimal Android take on Virtual Circuit Board: draw circuits on a pixel grid with coloured
"inks", then run them.

## Build

Open this `CircuitBoard` folder in Android Studio and press Run, or from a terminal with
JDK 17 and the Android SDK installed:

```
gradle assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
gradle testDebugUnitTest      # simulator unit tests
```

No third-party libraries are used. Min SDK 24 (Android 7.0).

Windows notes:
- If Gradle fails to download plugins with a TLS handshake error, set
  `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` so Java uses the Windows certificate store.
- Building the APK works from a path with non-ASCII characters, but unit tests do not
  (`ClassNotFoundException: GradleWorkerMain`). Run the tests from a copy at an ASCII-only path,
  with `GRADLE_USER_HOME` pointing to an ASCII-only path as well.

## Features

| Area        | What you get |
|-------------|--------------|
| Gates       | Buffer, NOT, AND, NAND, OR, NOR, XOR, XNOR (any shape, any number of inputs) |
| Traces      | 8 colours; different colours never join; R/W connectors define direction |
| I/O         | Const 0, Const 1, Clock (adjustable period), Switch (tap while running), Display (LED of any shape) |
| Compacting  | Cross, Tunnel, Mesh, Bus (3 colours) |
| Editing     | Draw, Erase, rectangle Select, Copy / Cut / Delete / Paste (with rotate and flip), undo / redo |
| View        | Pinch zoom, two-finger pan, +/−/Fit buttons |
| Simulation  | Run / Stop, Pause, Step, speed 1–10 000 ticks per second |
| Files       | Named saves inside the app, Export / Import `.vcb` files, autosave on exit |

## Rules (same as the in-app Help)

- Touching cells of the same ink form one part.
- A part reads through **R** cells and writes through **W** cells: `part → W → trace → R → part`.
  A trace is on when any part writing to it is on. Every part has a 1-tick delay.
- Gates, with A = active inputs and T = all inputs: Buffer `A>0`, AND `A>0 && A==T`, OR `A>0`,
  XOR `A odd`; NOT/NAND/NOR/XNOR are the negations.
- **Cross**: one cell where a horizontal and a vertical line pass without joining.
- **Tunnel**: a line entering a tunnel cell continues right after the next tunnel cell in the same
  direction whose far side has the same ink.
- **Mesh**: every cell of a given ink touching any mesh cell is joined, anywhere on the board.
- **Bus**: like mesh, but only within one connected area of one bus colour.

## Code map

- `Ink.kt` – ink ids, colours, glyphs, palette groups and which inks join.
- `Board.kt` – the grid, undo/redo, copy/paste, save/load (gzip).
- `Simulator.kt` – turns the grid into nets and parts (union-find), then runs ticks.
- `BoardView.kt` – rendering, touch gestures, selection, paste ghost, simulation loop.
- `MainActivity.kt` – toolbar, palette, dialogs, files, demo circuit.
