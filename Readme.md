# Java-Dll-Analyzer (MVP)

Java-Dll-Analyzer is a simple desktop Swing application (MVP) that helps inspect Windows DLL exports and call native functions from embedded scripts (BeanShell). This project targets Java 21 on Windows x64 and uses JNA for native interop.

Key features in this MVP:
- Load a Windows DLL and list exported symbols.
- Simple PE export table parser to extract exported names. Types/signatures are shown as "unknown" when not discoverable.
- RSyntaxTextArea-based code editor with syntax highlighting, undo/redo, basic autocomplete, and a minimal single-file rename refactoring.
- BeanShell script integration (fully implemented). The native functions are exposed through a dynamic proxy as `native` / `nativeLib` in the script context.
- Double-click an exported function to insert a call snippet into the editor.
- Execute scripts with a timeout (default 5000 ms). Stdout/stderr captured and shown in the UI.
- Project file format `jdan.json` (single script file configuration).

Build
-----
Requires JDK 21 and Maven.

mvn package

A runnable fat JAR will be produced (target/java-dll-analyzer-0.1.0-SNAPSHOT.jar).

Run
---
java -jar target/java-dll-analyzer-0.1.0-SNAPSHOT.jar

Usage
-----
1. Open -> Open DLL... and select a .dll file (Windows x64).
2. The left panel lists exported functions (names). If signatures are unknown, types are "unknown".
3. Double-click a function to insert a snippet into the editor. Snippet formats:
   - If return type known: [result=]functionName(arg0 /*type*/, arg1 /*type*/); // full signature
   - If return type unknown: functionName(arg0, arg1); // signature unknown: functionName
4. Scripts: Default script language is BeanShell. Example script that calls a native function:
   // Example (inserted snippet):
   [result=]Add(arg0 /*int*/, arg1 /*int*/); // int Add(int a, int b)

   // In BeanShell replace with:
   int res = ((Integer) native.Add(1, 2)).intValue();
   print("Result: " + res);

   Note: The dynamic proxy exposes native functions via `native` and `nativeLib`.

5. Click "Run Script" to execute. Output and exceptions appear in the console panel. Scripts that run longer than 5000 ms will be timed out.

Project file
------------
The project file is a JSON file (default name: `jdan.json`), schema:
{
  "dllPath": "<absolute-or-relative-path>",
  "scriptLanguage": "BeanShell"|"Python"|"JavaScript",
  "scriptFile": "scripts/main.bsh"
}

Save/Load project via File menu.

Limitations / TODOs
-------------------
- Export table parsing is minimal: only exported names are reliably extracted. Full signature detection (parameter counts/types/return type) is not implemented — most functions will be marked as "unknown".
- JNA proxy maps calls dynamically but does not automatically map complex signatures. For reliable calls, pass primitives and handle pointer buffers manually.
- Python and JavaScript script engines are placeholders. Integration via JSR-223/GraalVM can be added.
- The proxy currently exposes a generic Map-backed dynamic proxy. For better ergonomics, generate Java interfaces with typed methods when signatures are known.
- PE parsing edge cases (forwarders, ordinal-only exports) need more handling.

Next steps
----------
- Improve signature detection (symbol decoration parsing, debug/type info).
- Add multi-script projects and cross-file refactoring.
- Implement typed proxy generation for native functions.
- Add tests and CI, and packaging for Windows installer.

License
-------
This project is private 

Notes for testing with a sample DLL
----------------------------------
If you don't have a DLL to test, create a simple C file:

// add.c
__declspec(dllexport) int Add(int a, int b) {
    return a + b;
}

Build with MSVC or MinGW:
cl /LD add.c -Fe:add.dll
or with mingw:
gcc -shared -o add.dll add.c

Then open add.dll in the app and test.

Contact
-------
This is an MVP implementation. For improvements, extend DllParser and JnaProxyFactory as described above.