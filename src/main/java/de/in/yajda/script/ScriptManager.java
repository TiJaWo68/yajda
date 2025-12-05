package de.in.yajda.script;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import bsh.Interpreter;
import de.in.yajda.dll.JnaProxyFactory;

/**
 * Manages script execution and integration of native proxy into the script context. Currently fully implements BeanShell integration. Python/JavaScript hooks are left as TODO placeholders.
 */
public class ScriptManager {
	private final Consumer<String> consoleWriter;
	private long timeoutMs = 5000;
	private JnaProxyFactory.ProxyWrapper nativeProxy;

	private final ExecutorService executor = Executors.newCachedThreadPool();

	public ScriptManager(Consumer<String> consoleWriter, long timeoutMs) {
		this.consoleWriter = consoleWriter;
		this.timeoutMs = timeoutMs;
	}

	public void setNativeProxy(JnaProxyFactory.ProxyWrapper proxy) {
		this.nativeProxy = proxy;
	}

	public long getTimeoutMs() {
		return timeoutMs;
	}

	public static class ExecutionResult {
		public Object result;
		public String output;
		public Throwable threw;
		public boolean timedOut;
	}

	/**
	 * Execute script in given language. Currently supports BeanShell fully.
	 */
	public void executeScript(String scriptText, String language, Consumer<ExecutionResult> callback) {
		if ("BeanShell".equalsIgnoreCase(language)) {
			executeBeanShell(scriptText, callback);
		} else {
			// TODO: Hook for Python/JS via JSR-223 (GraalVM) - for now report placeholder
			ExecutionResult r = new ExecutionResult();
			r.threw = new UnsupportedOperationException("Language not implemented in MVP: " + language);
			callback.accept(r);
		}
	}

	private void executeBeanShell(String scriptText, Consumer<ExecutionResult> callback) {
		Future<ExecutionResult> fut = executor.submit(() -> {
			ExecutionResult er = new ExecutionResult();
			Interpreter interp = new Interpreter();
			// capture output
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			PrintStream ps = new PrintStream(bout);
			interp.setOut(ps);
			interp.setErr(ps);
			try {
				if (nativeProxy != null) {
					// register native proxy as 'nativeLib' and also as 'native' for historical expectation
					interp.set("native", nativeProxy.getProxyObject());
					interp.set("nativeLib", nativeProxy.getProxyObject());
				}
				// execute
				Object res = interp.eval(scriptText);
				ps.flush();
				er.output = bout.toString();
				er.result = res;
			} catch (Throwable t) {
				ps.flush();
				er.output = bout.toString();
				er.threw = t;
			} finally {
				try {
					ps.close();
				} catch (Exception ignored) {
				}
			}
			return er;
		});

		executor.submit(() -> {
			ExecutionResult er = new ExecutionResult();
			try {
				ExecutionResult res = fut.get(timeoutMs, TimeUnit.MILLISECONDS);
				callback.accept(res);
			} catch (TimeoutException te) {
				fut.cancel(true);
				er.timedOut = true;
				er.output = "Script timed out.";
				callback.accept(er);
			} catch (ExecutionException ee) {
				er.threw = ee.getCause();
				callback.accept(er);
			} catch (InterruptedException ie) {
				er.threw = ie;
				callback.accept(er);
			}
		});
	}
}