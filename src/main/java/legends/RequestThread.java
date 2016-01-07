package legends;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.reflections.ReflectionUtils;
import org.reflections.Reflections;

import com.google.common.base.Predicate;

import legends.helper.EventHelper;
import legends.model.World;
import legends.web.basic.Controller;
import legends.web.basic.RequestMapping;

public class RequestThread extends Thread {

	private static void sendError(final BufferedOutputStream out, final int code, String message) throws IOException {
		message = message + "<hr>" + PortalServer.VERSION;
		sendHeader(out, code, "text/html", message.length(), System.currentTimeMillis());
		out.write(message.getBytes());
		out.flush();
		out.close();
	}

	private static void sendHeader(final BufferedOutputStream out, final int code, final String contentType,
			final long contentLength, final long lastModified) throws IOException {
		out.write(("HTTP/1.0 " + code + " OK\r\n" + "Date: " + new Date().toString() + "\r\n"
				+ "Server: Legends Browser/1.0\r\n" + "Content-Type: " + contentType + "\r\n"
				+ "Expires: Thu, 01 Dec 1994 16:00:00 GMT\r\n"
				+ ((contentLength != -1) ? "Content-Length: " + contentLength + "\r\n" : "") + "Last-modified: "
				+ new Date(lastModified).toString() + "\r\n" + "\r\n").getBytes());
	}

	private final Socket _socket;

	public RequestThread(final Socket socket) {
		_socket = socket;
	}

	@Override
	public void run() {
		InputStream reader = null;
		try {
			_socket.setSoTimeout(30000);
			final BufferedReader in = new BufferedReader(new InputStreamReader(_socket.getInputStream()));
			final BufferedOutputStream out = new BufferedOutputStream(_socket.getOutputStream());

			final String request = in.readLine();
			if (request == null || !request.startsWith("GET ")
					|| !(request.endsWith(" HTTP/1.0") || request.endsWith("HTTP/1.1"))) {
				// Invalid request type (no "GET")
				sendError(out, 500, "Invalid Method.");
				return;
			}
			String path = request.substring(4, request.length() - 9);

			VelocityContext context = new VelocityContext();

			String arguments = "";
			HashMap<String, String> params = new HashMap<>();
			if (path.contains("?")) {
				arguments = path.substring(path.indexOf("?") + 1);
				path = path.substring(0, path.indexOf("?"));

				for (String kv : arguments.split("&")) {
					String[] d = kv.split("=");
					if (d.length == 2) {
						String key = URLDecoder.decode(d[0], "UTF-8");
						String value = URLDecoder.decode(d[1], "UTF-8");
						params.put(key, value);
						context.put(key, value);
					}
				}
			}
			path = path.replace("+", "%2B");
			path = URLDecoder.decode(path, "UTF-8");

			File file = new File(path).getCanonicalFile();

			if (file.isDirectory()) {
				// Check to see if there is an index file in the directory.
				final File indexFile = new File(file, "index.html");
				if (indexFile.exists() && !indexFile.isDirectory()) {
					file = indexFile;
				}
			}

			if (path.startsWith("/map")) {
				if (World.getMapFile() != null)
					writeFile(out, World.getMapFile(), "image/png");
				else
					sendError(out, 400, "Map image not loaded");

			} else if (!path.startsWith("/resources")) {
				try {
					StringWriter sw = new StringWriter();

					context.put("World", World.class);
					context.put("Event", EventHelper.class);

					context.put("contentType", "text/html");

					Template template = findMapping(path, context);
					if (template == null)
						template = Velocity.getTemplate("index.vm");

					template.merge(context, sw);
					String content = sw.toString();

					sendHeader(out, 200, (String) context.get("contentType"), content.length(), file.lastModified());
					out.write(content.getBytes());
				} catch (Exception e) {
					e.printStackTrace();
					String content = e.getMessage();
					sendHeader(out, 200, "text/html", content.length(), file.lastModified());
					out.write(content.getBytes());
				}
			} else {
				String contentType = PortalServer.MIME_TYPES.get(path.substring(path.lastIndexOf(".")).toLowerCase());
				if (contentType == null) {
					contentType = "application/octet-stream";
				}

				try {
					final URLConnection c = ClassLoader.getSystemResource(path.substring("/resources/".length()))
							.openConnection();
					sendHeader(out, 200, contentType, c.getContentLength(), c.getLastModified());
					reader = new BufferedInputStream(c.getInputStream());

					final byte[] buffer = new byte[4096];
					int bytesRead;
					while ((bytesRead = reader.read(buffer)) != -1) {
						out.write(buffer, 0, bytesRead);
					}
					reader.close();
				} catch (final Exception e) {
					System.err.println(path);
					e.printStackTrace();
				}
			}

			out.flush();
			out.close();

			if (path.startsWith("/exit"))
				System.exit(0);
		} catch (final IOException e) {
			if (reader != null) {
				try {
					reader.close();
				} catch (final Exception anye) {
				}
			}
		}
	}

	private Template findMapping(final String path, final VelocityContext context)
			throws InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {

		Predicate<AnnotatedElement> withMapping = ReflectionUtils.withAnnotation(RequestMapping.class);

		Reflections reflections = new Reflections("legends.web");
		Set<Class<?>> controllers = reflections.getTypesAnnotatedWith(Controller.class);

		List<Method> methods = new ArrayList<Method>();

		for (Class<?> controller : controllers) {
			WorldState state = controller.getAnnotation(Controller.class).state();
			if (state != World.getState() && state != WorldState.ANY)
				continue;

			methods.addAll(ReflectionUtils.getAllMethods(controller, withMapping));
		}

		Collections.sort(methods, (m1, m2) -> {
			String map1 = m1.getAnnotation(RequestMapping.class).value();
			String map2 = m2.getAnnotation(RequestMapping.class).value();
			return map1.length() < map2.length() ? 1 : -1;
		});

		Method defaultMethod = null;
		for (Method method : methods) {
			String mapping = method.getAnnotation(RequestMapping.class).value();

			if (mapping.equals("")) {
				defaultMethod = method;
				continue;
			}

			if (mapping.endsWith("{id}")) {
				mapping = mapping.substring(0, mapping.length() - ("{id}".length()));

				if (path.startsWith(mapping)) {
					int id = Integer.parseInt(path.substring(mapping.length()));

					return (Template) method.invoke(method.getDeclaringClass().newInstance(), context, id);
				} else {
					continue;
				}

			} else if (mapping.endsWith("{name}")) {
				mapping = mapping.substring(0, mapping.length() - ("{name}".length()));

				if (path.startsWith(mapping)) {
					String name = path.substring(mapping.length());

					return (Template) method.invoke(method.getDeclaringClass().newInstance(), context, name);
				} else {
					continue;
				}

			} else if (!path.startsWith(mapping)) {
				continue;
			}

			return (Template) method.invoke(method.getDeclaringClass().newInstance(), context);

		}

		if (defaultMethod != null) {
			return (Template) defaultMethod.invoke(defaultMethod.getDeclaringClass().newInstance(), context);
		}

		return null;
	}

	private void writeFile(BufferedOutputStream out, File file, String contentType)
			throws MalformedURLException, IOException {
		final URLConnection c = file.toURI().toURL().openConnection();
		sendHeader(out, 200, contentType, c.getContentLength(), c.getLastModified());
		BufferedInputStream reader = new BufferedInputStream(c.getInputStream());

		final byte[] buffer = new byte[640000];
		int bytesRead;
		while ((bytesRead = reader.read(buffer)) != -1) {
			out.write(buffer, 0, bytesRead);
		}
		reader.close();
	}

}