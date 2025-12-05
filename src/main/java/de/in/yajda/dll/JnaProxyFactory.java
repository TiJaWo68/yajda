package de.in.yajda.dll;

import java.lang.reflect.InvocationHandler;
import java.util.HashMap;
import java.util.Map;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;

/**
 * Creates a dynamic proxy "native" object whose method calls are routed to functions inside a DLL using JNA Function.invoke.
 *
 * Usage in scripts (BeanShell): native.Add(1, 2);
 *
 * Note: This proxy attempts to map common primitive Java types to native types. Signature detection is limited; for unknowns the caller must pass appropriate Java primitives.
 */
public class JnaProxyFactory {
	private final NativeLibrary lib;
	private final Map<String, Function> functions = new HashMap<>();

	public static class ProxyWrapper {
		private final Object proxyObject;

		public ProxyWrapper(Object o) {
			this.proxyObject = o;
		}

		public Object getProxyObject() {
			return proxyObject;
		}
	}

	public JnaProxyFactory(String dllPath) {
		this.lib = NativeLibrary.getInstance(dllPath);
		// Note: do not pre-enumerate; will lookup lazily
	}

	public ProxyWrapper createNativeProxy() {
		InvocationHandler handler = (proxy, method, args) -> {
			String name = method.getName();
			Function f = functions.computeIfAbsent(name, n -> {
				try {
					return lib.getFunction(n);
				} catch (UnsatisfiedLinkError e) {
					return null;
				}
			});
			if (f == null) {
				throw new NoSuchMethodError("Native function not found: " + name);
			}
			// Map args and attempt to infer return type from Java method return type
			Class<?> ret = method.getReturnType();
			Object result;
			try {
				// If method declared return type is void, pass VOID
				if (ret == Void.TYPE) {
					f.invokeVoid(args == null ? new Object[0] : args);
					return null;
				}
				// Choose a mapping for return type
				Class<?> mapRet = mapReturnType(ret);
				return f.invoke(mapRet, args == null ? new Object[0] : args);
			} catch (Throwable t) {
				throw t;
			}
		};

		// Create a dynamic proxy; fully qualify to avoid name clash with ProxyWrapper
		Object p = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Map.class }, handler);
		return new ProxyWrapper(p);
	}

	private Class<?> mapReturnType(Class<?> ret) {
		if (ret == Integer.class || ret == int.class)
			return Integer.class;
		if (ret == Long.class || ret == long.class)
			return Long.class;
		if (ret == Float.class || ret == float.class)
			return Float.class;
		if (ret == Double.class || ret == double.class)
			return Double.class;
		if (ret == Void.TYPE)
			return Void.TYPE;
		// default raw pointer as long
		return Long.class;
	}
}