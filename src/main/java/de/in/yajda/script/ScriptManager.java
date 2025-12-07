package de.in.yajda.script;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;

import bsh.Interpreter;
import de.in.yajda.dll.JnaProxyFactory;

/**
 * ScriptManager that: - binds a native proxy (wrapper) into BeanShell as 'dll' - can generate a BeanShell wrapper object with methods for
 * each native function name (overloads 0..6 args + varargs) - provides a NativeInvoker that actually dispatches calls (via reflection on
 * proxy or via JNA Function)
 *
 * Robustness: merkt sich die zuletzt übergebenen Funktionsnamen und stellt vor jeder Ausführung sicher, dass die Variable 'dll' im
 * Interpreter existiert (erzeugt Wrapper bei Bedarf neu).
 */
public class ScriptManager {

	public static class ScriptResult {
		public boolean timedOut;
		public Throwable threw;
		public Object result;
		public String output;
	}

	private final Consumer<String> consoleAppender;
	private final long timeoutMs;
	private final Interpreter interpreter;
	private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "script-runner");
		t.setDaemon(true);
		return t;
	});

	private volatile JnaProxyFactory.ProxyWrapper nativeProxy; // wrapper object as provided by your factory
	private volatile java.io.File currentDllFile; // used for JNA fallback lookups

	// Merken der zuletzt übergebenen Namen, damit Wrapper bei Bedarf neu erzeugt werden kann
	private volatile Collection<String> availableFunctionNames = Collections.emptyList();

	public ScriptManager(Consumer<String> consoleAppender, long timeoutMs) {
		this.consoleAppender = consoleAppender;
		this.timeoutMs = timeoutMs;
		this.interpreter = new Interpreter();

		// create PrintStream forwarding to consoleAppender
		PrintStream ps;
		try {
			ps = new PrintStream(new java.io.OutputStream() {
				private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

				@Override
				public synchronized void write(int b) {
					buf.write(b);
					if (b == '\n')
						flushBuffer();
				}

				@Override
				public synchronized void write(byte[] b, int off, int len) {
					buf.write(b, off, len);
					for (int i = off; i < off + len; i++) {
						if (b[i] == '\n')
							flushBuffer();
					}
				}

				@Override
				public synchronized void flush() {
					flushBuffer();
				}

				private void flushBuffer() {
					if (buf.size() == 0)
						return;
					String s = buf.toString(StandardCharsets.UTF_8);
					buf.reset();
					if (s != null && !s.isEmpty()) {
						consoleAppender.accept(s);
					}
				}
			}, true, StandardCharsets.UTF_8.name());
		} catch (Exception e) {
			ps = new PrintStream(System.out, true);
		}

		// bind interpreter output to our PrintStream
		try {
			interpreter.setOut(ps);
			interpreter.setErr(ps);
		} catch (Exception e) {
			consoleAppender.accept("Warning: failed to set interpreter output stream: " + e.getMessage());
		}

		// provide an invoker object in the interpreter which will be called from the
		// dynamically generated wrapper
		try {
			interpreter.set("dllInvoker", new NativeInvoker());
		} catch (Exception e) {
			consoleAppender.accept("Warning: could not bind dllInvoker: " + e.getMessage());
		}
	}

	/**
	 * Set the ProxyWrapper produced by your JnaProxyFactory. We'll try to resolve proxied object when invoking.
	 */
	public void setNativeProxy(JnaProxyFactory.ProxyWrapper proxy) {
		this.nativeProxy = proxy;
		try {
			interpreter.set("nativeProxy", proxy);
		} catch (Exception ignored) {
		}
	}

	/**
	 * Set the currently loaded DLL file (used by NativeInvoker as fallback to call Functions via JNA).
	 */
	public void setCurrentDllFile(java.io.File dllFile) {
		this.currentDllFile = dllFile;
	}

	/**
	 * Generate a BeanShell wrapper class with methods for each function name and bind an instance as "dll". Call this after you've loaded
	 * the DLL and know its exported names.
	 *
	 * Overloads generated: 0..6 arguments (Object typed), plus a varargs method name_v(Object[] args).
	 */
	public synchronized void setAvailableFunctionNames(Collection<String> names) {
		if (names == null)
			names = Collections.emptyList();
		// store for future re-creation if necessary
		this.availableFunctionNames = Collections.unmodifiableCollection(names);

		StringBuilder sb = new StringBuilder();
		String className = "_DLLWrapper_" + System.nanoTime();
		sb.append("class ").append(className).append(" {\n");
		sb.append("  Object invoker;\n");
		sb.append("  ").append(className).append("(Object inv) { this.invoker = inv; }\n");

		for (String raw : names) {
			if (raw == null || raw.isBlank())
				continue;
			String name = raw.trim();
			if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*"))
				continue;

			// 0-arg
			sb.append("  Object ").append(name).append("() { return invoker.invoke(\"").append(name).append("\", new Object[]{}); }\n");

			// overloads 1..6
			for (int ar = 1; ar <= 6; ar++) {
				sb.append("  Object ").append(name).append("(");
				for (int i = 1; i <= ar; i++) {
					if (i > 1)
						sb.append(", ");
					sb.append("Object a").append(i);
				}
				sb.append(") {\n");
				sb.append("    Object[] arr = new Object[").append(ar).append("];\n");
				for (int i = 1; i <= ar; i++) {
					sb.append("    arr[").append(i - 1).append("] = a").append(i).append(";\n");
				}
				sb.append("    return invoker.invoke(\"").append(name).append("\", arr);\n");
				sb.append("  }\n");
			}

			// varargs array form
			sb.append("  Object ").append(name).append("_v(Object[] args) { return invoker.invoke(\"").append(name)
					.append("\", args); }\n");
		}

		// generic invoke
		sb.append("  Object invoke(String name, Object[] args) { return invoker.invoke(name, args); }\n");
		sb.append("}\n");

		try {
			interpreter.eval(sb.toString());
			Object wrapper = interpreter.eval("new " + className + "(dllInvoker)");
			interpreter.set("dll", wrapper);
			consoleAppender.accept("Script wrapper 'dll' created with " + names.size() + " functions.");
		} catch (Throwable t) {
			consoleAppender.accept("Failed to create DLL wrapper in script interpreter: " + t.getMessage());
		}
	}

	/**
	 * Ensure that the wrapper 'dll' exists in the interpreter. If not present but we have remembered function names, recreate it.
	 */
	private synchronized void ensureWrapperPresent() {
		try {
			Object existing = interpreter.get("dll");
			if (existing == null && availableFunctionNames != null && !availableFunctionNames.isEmpty()) {
				// recreate wrapper
				setAvailableFunctionNames(availableFunctionNames);
			}
		} catch (Throwable t) {
			// interpreter.get may throw in some environments; ignore but log
			consoleAppender.accept("Warning: could not check 'dll' binding: " + t.getMessage());
		}
	}

	/**
	 * Execute a script (BeanShell). Callback receives ScriptResult on completion.
	 */
	public void executeScript(String scriptText, String language, Consumer<ScriptResult> callback) {
		Future<ScriptResult> fut = exec.submit(() -> {
			ScriptResult res = new ScriptResult();
			try {
				// ensure up-to-date binding for nativeProxy (used by NativeInvoker)
				try {
					interpreter.set("nativeProxy", nativeProxy);
				} catch (Exception ignored) {
				}

				// ensure wrapper exists (in case it was not created or binding got lost)
				ensureWrapperPresent();

				Object r = interpreter.eval(scriptText);
				res.result = r;
			} catch (Throwable t) {
				res.threw = t;
			}
			return res;
		});

		// manage timeout separately
		exec.submit(() -> {
			try {
				ScriptResult r = fut.get(timeoutMs, TimeUnit.MILLISECONDS);
				callback.accept(r);
			} catch (TimeoutException te) {
				fut.cancel(true);
				ScriptResult rr = new ScriptResult();
				rr.timedOut = true;
				callback.accept(rr);
			} catch (ExecutionException ee) {
				ScriptResult rr = new ScriptResult();
				rr.threw = ee.getCause();
				callback.accept(rr);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				ScriptResult rr = new ScriptResult();
				rr.threw = ie;
				callback.accept(rr);
			}
		});
	}

	public long getTimeoutMs() {
		return timeoutMs;
	}

	public void shutdown() {
		exec.shutdownNow();
	}

	/**
	 * NativeInvoker: exposed into BeanShell as 'dllInvoker'.invoke(name, args) Attempts: 1) resolve proxied object and call method by name
	 * via reflection 2) if method not found, try to call an 'invoke' style method on the wrapper via reflection 3) fallback: try JNA
	 * Function lookup on currentDllFile and call it
	 */
	public class NativeInvoker {
		public Object invoke(String name, Object[] args) throws Exception {
			// 1) try direct reflection on proxied object
			Object proxyObj = resolveProxyObject(nativeProxy);
			if (proxyObj != null) {
				Method[] methods = proxyObj.getClass().getMethods();
				for (Method m : methods) {
					if (!m.getName().equals(name))
						continue;
					if (m.getParameterCount() == (args == null ? 0 : args.length)) {
						try {
							return m.invoke(proxyObj, args == null ? new Object[] {} : args);
						} catch (Throwable t) {
							// try next candidate
						}
					}
				}
			}

			// 2) try wrapper-level dynamic invoke methods like 'invoke(String,Object[])' on the wrapper itself
			if (nativeProxy != null) {
				Method[] wmethods = nativeProxy.getClass().getMethods();
				for (Method m : wmethods) {
					if (m.getName().equals("invoke") || m.getName().equals("call") || m.getName().equals("invokeFunction")) {
						Class<?>[] p = m.getParameterTypes();
						if (p.length == 2 && p[0] == String.class && p[1] == Object[].class) {
							try {
								return m.invoke(nativeProxy, name, args == null ? new Object[] {} : args);
							} catch (Throwable t) {
								/* ignore */ }
						}
					}
				}
			}

			// 3) fallback: try JNA Function lookup by name (requires currentDllFile)
			if (currentDllFile != null && currentDllFile.exists()) {
				try {
					NativeLibrary lib = NativeLibrary.getInstance(currentDllFile.getAbsolutePath());
					Function f = lib.getFunction(name);
					Object[] a = args == null ? new Object[] {} : args;
					return f.invoke(Object.class, a);
				} catch (Throwable t) {
					throw new RuntimeException("Native call failed for " + name + ": " + t.getMessage(), t);
				}
			}

			throw new NoSuchMethodException("Native method '" + name + "' not found on proxy and no fallback available");
		}
	}

	/**
	 * Try to obtain the underlying proxied object from the wrapper using common accessor names. If nothing found, return the wrapper itself
	 * (may still be usable).
	 */
	private Object resolveProxyObject(JnaProxyFactory.ProxyWrapper wrapper) {
		if (wrapper == null)
			return null;
		String[] candidates = { "getProxy", "getNativeProxy", "getProxyObject", "getProxyImpl", "getNative" };
		for (String name : candidates) {
			try {
				Method m = wrapper.getClass().getMethod(name);
				if (m != null) {
					try {
						Object res = m.invoke(wrapper);
						if (res != null)
							return res;
					} catch (Throwable ignored) {
					}
				}
			} catch (NoSuchMethodException ignored) {
			}
		}
		// fallback: return wrapper itself
		return wrapper;
	}
}