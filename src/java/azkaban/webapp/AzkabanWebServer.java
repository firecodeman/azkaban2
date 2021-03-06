/*
 * Copyright 2012 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package azkaban.webapp;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;

import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.log.Log4JLogChute;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.apache.velocity.runtime.resource.loader.JarResourceLoader;
import org.joda.time.DateTimeZone;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.bio.SocketConnector;
import org.mortbay.jetty.security.SslSocketConnector;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.DefaultServlet;
import org.mortbay.jetty.servlet.ServletHolder;
import org.mortbay.thread.QueuedThreadPool;

import azkaban.database.AzkabanDatabaseSetup;
import azkaban.executor.ExecutorManager;
import azkaban.executor.JdbcExecutorLoader;
import azkaban.jmx.JmxExecutorManager;
import azkaban.jmx.JmxJettyServer;
import azkaban.jmx.JmxSLAManager;
import azkaban.jmx.JmxScheduler;
import azkaban.project.JdbcProjectLoader;
import azkaban.project.ProjectManager;

import azkaban.scheduler.JdbcScheduleLoader;
import azkaban.scheduler.ScheduleManager;
import azkaban.sla.JdbcSLALoader;
import azkaban.sla.SLAManager;
import azkaban.sla.SLAManagerException;
import azkaban.user.UserManager;
import azkaban.user.XmlUserManager;
import azkaban.utils.FileIOUtils;
import azkaban.utils.Props;
import azkaban.utils.PropsUtils;
import azkaban.utils.Utils;
import azkaban.webapp.servlet.AzkabanServletContextListener;

import azkaban.webapp.servlet.AbstractAzkabanServlet;
import azkaban.webapp.servlet.ExecutorServlet;
import azkaban.webapp.servlet.JMXHttpServlet;
import azkaban.webapp.servlet.ScheduleServlet;
import azkaban.webapp.servlet.HistoryServlet;
import azkaban.webapp.servlet.IndexServlet;
import azkaban.webapp.servlet.ProjectManagerServlet;
import azkaban.webapp.servlet.ViewerPlugin;
import azkaban.webapp.session.SessionCache;

/**
 * The Azkaban Jetty server class
 * 
 * Global azkaban properties for setup. All of them are optional unless
 * otherwise marked: azkaban.name - The displayed name of this instance.
 * azkaban.label - Short descriptor of this Azkaban instance. azkaban.color -
 * Theme color azkaban.temp.dir - Temp dir used by Azkaban for various file
 * uses. web.resource.dir - The directory that contains the static web files.
 * default.timezone.id - The timezone code. I.E. America/Los Angeles
 * 
 * user.manager.class - The UserManager class used for the user manager. Default
 * is XmlUserManager. project.manager.class - The ProjectManager to load
 * projects project.global.properties - The base properties inherited by all
 * projects and jobs
 * 
 * jetty.maxThreads - # of threads for jetty jetty.ssl.port - The ssl port used
 * for sessionizing. jetty.keystore - Jetty keystore . jetty.keypassword - Jetty
 * keystore password jetty.truststore - Jetty truststore jetty.trustpassword -
 * Jetty truststore password
 */
public class AzkabanWebServer extends AzkabanServer {
	private static final Logger logger = Logger.getLogger(AzkabanWebServer.class);
	
	public static final String AZKABAN_HOME = "AZKABAN_HOME";
	public static final String DEFAULT_CONF_PATH = "conf";
	public static final String AZKABAN_PROPERTIES_FILE = "azkaban.properties";
	public static final String AZKABAN_PRIVATE_PROPERTIES_FILE = "azkaban.private.properties";

	private static final int MAX_FORM_CONTENT_SIZE = 10*1024*1024;
	private static final int MAX_HEADER_BUFFER_SIZE = 10*1024*1024;
	private static AzkabanWebServer app;

	private static final String DEFAULT_TIMEZONE_ID = "default.timezone.id";
	private static final int DEFAULT_PORT_NUMBER = 8081;
	private static final int DEFAULT_SSL_PORT_NUMBER = 8443;
	private static final int DEFAULT_THREAD_NUMBER = 20;
	private static final String VELOCITY_DEV_MODE_PARAM = "velocity.dev.mode";
	private static final String USER_MANAGER_CLASS_PARAM = "user.manager.class";
	private static final String DEFAULT_STATIC_DIR = "";

	private final VelocityEngine velocityEngine;
	
	private final Server server;
	private UserManager userManager;
	private ProjectManager projectManager;
	private ExecutorManager executorManager;
	private ScheduleManager scheduleManager;
	private SLAManager slaManager;

	private final ClassLoader baseClassLoader;
	
	private Props props;
	private SessionCache sessionCache;
	private File tempDir;
	private List<ViewerPlugin> viewerPlugins;
	
	private MBeanServer mbeanServer;
	private ArrayList<ObjectName> registeredMBeans = new ArrayList<ObjectName>();

	/**
	 * Constructor usually called by tomcat AzkabanServletContext to create the
	 * initial server
	 */
	public AzkabanWebServer() throws Exception {
		this(null, loadConfigurationFromAzkabanHome());
	}

	/**
	 * Constructor
	 */
	public AzkabanWebServer(Server server, Props props) throws Exception {
		this.props = props;
		this.server = server;
		velocityEngine = configureVelocityEngine(props.getBoolean(VELOCITY_DEV_MODE_PARAM, false));
		sessionCache = new SessionCache(props);
		userManager = loadUserManager(props);
		projectManager = loadProjectManager(props);
		executorManager = loadExecutorManager(props);
		slaManager = loadSLAManager(props);
		scheduleManager = loadScheduleManager(executorManager, slaManager, props);
		baseClassLoader = getBaseClassloader();
		
		tempDir = new File(props.getString("azkaban.temp.dir", "temp"));

		// Setup time zone
		if (props.containsKey(DEFAULT_TIMEZONE_ID)) {
			String timezone = props.getString(DEFAULT_TIMEZONE_ID);
			TimeZone.setDefault(TimeZone.getTimeZone(timezone));
			DateTimeZone.setDefault(DateTimeZone.forID(timezone));

			logger.info("Setting timezone to " + timezone);
		}
		
		configureMBeanServer();
	}

	private void setViewerPlugins(List<ViewerPlugin> viewerPlugins) {
		this.viewerPlugins = viewerPlugins;
	}
	
	private UserManager loadUserManager(Props props) {
		Class<?> userManagerClass = props.getClass(USER_MANAGER_CLASS_PARAM, null);
		logger.info("Loading user manager class " + userManagerClass.getName());
		UserManager manager = null;

		if (userManagerClass != null && userManagerClass.getConstructors().length > 0) {

			try {
				Constructor<?> userManagerConstructor = userManagerClass.getConstructor(Props.class);
				manager = (UserManager) userManagerConstructor.newInstance(props);
			} 
			catch (Exception e) {
				logger.error("Could not instantiate UserManager "+ userManagerClass.getName());
				throw new RuntimeException(e);
			}
		} 
		else {
			manager = new XmlUserManager(props);
		}

		return manager;
	}
	
	private ProjectManager loadProjectManager(Props props) {
		logger.info("Loading JDBC for project management");

		JdbcProjectLoader loader = new JdbcProjectLoader(props);
		ProjectManager manager = new ProjectManager(loader, props);
		
		return manager;
	}

	private ExecutorManager loadExecutorManager(Props props) throws Exception {
		JdbcExecutorLoader loader = new JdbcExecutorLoader(props);
		ExecutorManager execManager = new ExecutorManager(props, loader);
		return execManager;
	}

	private ScheduleManager loadScheduleManager(ExecutorManager execManager, SLAManager slaManager, Props props ) throws Exception {
		ScheduleManager schedManager = new ScheduleManager(execManager, projectManager, slaManager, new JdbcScheduleLoader(props));

		return schedManager;
	}

	private SLAManager loadSLAManager(Props props) throws SLAManagerException {
		SLAManager slaManager = new SLAManager(executorManager, new JdbcSLALoader(props), props);
		return slaManager;
	}
	
	/**
	 * Returns the web session cache.
	 * 
	 * @return
	 */
	public SessionCache getSessionCache() {
		return sessionCache;
	}

	/**
	 * Returns the velocity engine for pages to use.
	 * 
	 * @return
	 */
	public VelocityEngine getVelocityEngine() {
		return velocityEngine;
	}

	/**
	 * 
	 * @return
	 */
	public UserManager getUserManager() {
		return userManager;
	}

	/**
	 * 
	 * @return
	 */
	public ProjectManager getProjectManager() {
		return projectManager;
	}

	/**
     * 
     */
	public ExecutorManager getExecutorManager() {
		return executorManager;
	}
	
	public SLAManager getSLAManager() {
		return slaManager;
	}
	
	public ScheduleManager getScheduleManager() {
		return scheduleManager;
	}
	
	/**
	 * Creates and configures the velocity engine.
	 * 
	 * @param devMode
	 * @return
	 */
	private VelocityEngine configureVelocityEngine(final boolean devMode) {
		VelocityEngine engine = new VelocityEngine();
		engine.setProperty("resource.loader", "classpath, jar");
		engine.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
		engine.setProperty("classpath.resource.loader.cache", !devMode);
		engine.setProperty("classpath.resource.loader.modificationCheckInterval", 5L);
		engine.setProperty("jar.resource.loader.class", JarResourceLoader.class.getName());
		engine.setProperty("jar.resource.loader.cache", !devMode);
		engine.setProperty("resource.manager.logwhenfound", false);
		engine.setProperty("input.encoding", "UTF-8");
		engine.setProperty("output.encoding", "UTF-8");
		engine.setProperty("directive.set.null.allowed", true);
		engine.setProperty("resource.manager.logwhenfound", false);
		engine.setProperty("velocimacro.permissions.allow.inline", true);
		engine.setProperty("velocimacro.library.autoreload", devMode);
		engine.setProperty("velocimacro.library", "/azkaban/webapp/servlet/velocity/macros.vm");
		engine.setProperty("velocimacro.permissions.allow.inline.to.replace.global", true);
		engine.setProperty("velocimacro.arguments.strict", true);
		engine.setProperty("runtime.log.invalid.references", devMode);
		engine.setProperty("runtime.log.logsystem.class", Log4JLogChute.class);
		engine.setProperty("runtime.log.logsystem.log4j.logger", Logger.getLogger("org.apache.velocity.Logger"));
		engine.setProperty("parser.pool.size", 3);
		return engine;
	}

	private ClassLoader getBaseClassloader() throws MalformedURLException {
		final ClassLoader retVal;

		String hadoopHome = System.getenv("HADOOP_HOME");
		String hadoopConfDir = System.getenv("HADOOP_CONF_DIR");

		if (hadoopConfDir != null) {
			logger.info("Using hadoop config found in " + hadoopConfDir);
			retVal = new URLClassLoader(new URL[] { new File(hadoopConfDir)
					.toURI().toURL() }, getClass().getClassLoader());
		} else if (hadoopHome != null) {
			logger.info("Using hadoop config found in " + hadoopHome);
			retVal = new URLClassLoader(
					new URL[] { new File(hadoopHome, "conf").toURI().toURL() },
					getClass().getClassLoader());
		} else {
			logger.info("HADOOP_HOME not set, using default hadoop config.");
			retVal = getClass().getClassLoader();
		}

		return retVal;
	}

	public ClassLoader getClassLoader() {
		return baseClassLoader;
	}

	/**
	 * Returns the global azkaban properties
	 * 
	 * @return
	 */
	public Props getServerProps() {
		return props;
	}

	/**
	 * Azkaban using Jetty
	 * 
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
		logger.error("Starting Jetty Azkaban Executor...");
		Props azkabanSettings = AzkabanServer.loadProps(args);

		if (azkabanSettings == null) {
			logger.error("Azkaban Properties not loaded.");
			logger.error("Exiting Azkaban...");
			return;
		}

		int maxThreads = azkabanSettings.getInt("jetty.maxThreads", DEFAULT_THREAD_NUMBER);

		boolean ssl;
		int port;
		final Server server = new Server();
		if (azkabanSettings.getBoolean("jetty.use.ssl", true)) {
			int sslPortNumber = azkabanSettings.getInt("jetty.ssl.port", DEFAULT_SSL_PORT_NUMBER);
			port = sslPortNumber;
			ssl = true;
			logger.info("Setting up Jetty Https Server with port:" + sslPortNumber + " and numThreads:" + maxThreads);
			
			SslSocketConnector secureConnector = new SslSocketConnector();
			secureConnector.setPort(sslPortNumber);
			secureConnector.setKeystore(azkabanSettings.getString("jetty.keystore"));
			secureConnector.setPassword(azkabanSettings.getString("jetty.password"));
			secureConnector.setKeyPassword(azkabanSettings.getString("jetty.keypassword"));
			secureConnector.setTruststore(azkabanSettings.getString("jetty.truststore"));
			secureConnector.setTrustPassword(azkabanSettings.getString("jetty.trustpassword"));
			secureConnector.setHeaderBufferSize(MAX_HEADER_BUFFER_SIZE);
			
			server.addConnector(secureConnector);
		}
		else {
			ssl = false;
			port = azkabanSettings.getInt("jetty.port", DEFAULT_PORT_NUMBER);
			SocketConnector connector = new SocketConnector();
			connector.setPort(port);
			connector.setHeaderBufferSize(MAX_HEADER_BUFFER_SIZE);
			server.addConnector(connector);
		}
		
		String hostname = azkabanSettings.getString("jetty.hostname", "localhost");
		azkabanSettings.put("server.hostname", hostname);
		azkabanSettings.put("server.port", port);
		azkabanSettings.put("server.useSSL", String.valueOf(ssl));

		app = new AzkabanWebServer(server, azkabanSettings);
		
		boolean checkDB = azkabanSettings.getBoolean(AzkabanDatabaseSetup.DATABASE_CHECK_VERSION, false);
		if (checkDB) {
			AzkabanDatabaseSetup setup = new AzkabanDatabaseSetup(azkabanSettings);
			setup.loadTableInfo();
			if(setup.needsUpdating()) {
				logger.error("Database is out of date.");
				setup.printUpgradePlan();
				
				logger.error("Exiting with error.");
				System.exit(-1);
			}
		}
		
		QueuedThreadPool httpThreadPool = new QueuedThreadPool(maxThreads);
		server.setThreadPool(httpThreadPool);

		String staticDir = azkabanSettings.getString("web.resource.dir", DEFAULT_STATIC_DIR);
		logger.info("Setting up web resource dir " + staticDir);
		Context root = new Context(server, "/", Context.SESSIONS);
		root.setMaxFormContentSize(MAX_FORM_CONTENT_SIZE);
		
		root.setResourceBase(staticDir);
		ServletHolder index = new ServletHolder(new IndexServlet());
		root.addServlet(index, "/index");
		root.addServlet(index, "/");

		ServletHolder staticServlet = new ServletHolder(new DefaultServlet());
		root.addServlet(staticServlet, "/css/*");
		root.addServlet(staticServlet, "/js/*");
		root.addServlet(staticServlet, "/images/*");
		root.addServlet(staticServlet, "/favicon.ico");
		
		root.addServlet(new ServletHolder(new ProjectManagerServlet()),"/manager");
		root.addServlet(new ServletHolder(new ExecutorServlet()),"/executor");
		root.addServlet(new ServletHolder(new HistoryServlet()), "/history");
		root.addServlet(new ServletHolder(new ScheduleServlet()),"/schedule");
		root.addServlet(new ServletHolder(new JMXHttpServlet()),"/jmx");
		
		String viewerPluginDir = azkabanSettings.getString("viewer.plugin.dir", "plugins/viewer");
		app.setViewerPlugins(loadViewerPlugins(root, viewerPluginDir, app.getVelocityEngine()));

		root.setAttribute(AzkabanServletContextListener.AZKABAN_SERVLET_CONTEXT_KEY, app);
		try {
			server.start();
		} 
		catch (Exception e) {
			logger.warn(e);
			Utils.croak(e.getMessage(), 1);
		}

		Runtime.getRuntime().addShutdownHook(new Thread() {

			public void run() {
				logger.info("Shutting down http server...");
				try {
					app.close();
					server.stop();
					server.destroy();
				} 
				catch (Exception e) {
					logger.error("Error while shutting down http server.", e);
				}
				logger.info("kk thx bye.");
			}
		});
		logger.info("Server running on " + (ssl ? "ssl" : "") + " port " + port + ".");
	}

	private static List<ViewerPlugin> loadViewerPlugins(Context root, String pluginPath, VelocityEngine ve) {
		File viewerPluginPath = new File(pluginPath);
		if (!viewerPluginPath.exists()) {
			return Collections.<ViewerPlugin>emptyList();
		}
			
		ArrayList<ViewerPlugin> installedViewerPlugins = new ArrayList<ViewerPlugin>();
		ClassLoader parentLoader = AzkabanWebServer.class.getClassLoader();
		File[] pluginDirs = viewerPluginPath.listFiles();
		ArrayList<String> jarPaths = new ArrayList<String>();
		for (File pluginDir: pluginDirs) {
			if (!pluginDir.exists()) {
				logger.error("Error viewer plugin path " + pluginDir.getPath() + " doesn't exist.");
				continue;
			}
			
			if (!pluginDir.isDirectory()) {
				logger.error("The plugin path " + pluginDir + " is not a directory.");
				continue;
			}
			
			// Load the conf directory
			File propertiesDir = new File(pluginDir, "conf");
			Props pluginProps = null;
			if (propertiesDir.exists() && propertiesDir.isDirectory()) {
				File propertiesFile = new File(propertiesDir, "plugin.properties");
				File propertiesOverrideFile = new File(propertiesDir, "override.properties");
				
				if (propertiesFile.exists()) {
					if (propertiesOverrideFile.exists()) {
						pluginProps = PropsUtils.loadProps(null, propertiesFile, propertiesOverrideFile);
					}
					else {
						pluginProps = PropsUtils.loadProps(null, propertiesFile);
					}
				}
				else {
					logger.error("Plugin conf file " + propertiesFile + " not found.");
					continue;
				}
			}
			else {
				logger.error("Plugin conf path " + propertiesDir + " not found.");
				continue;
			}
			
			String pluginName = pluginProps.getString("viewer.name");
			String pluginWebPath = pluginProps.getString("viewer.path");
			int pluginOrder = pluginProps.getInt("viewer.order", 0);
			boolean pluginHidden = pluginProps.getBoolean("viewer.hidden", false);
			List<String> extLibClasspath = pluginProps.getStringList("viewer.external.classpaths", (List<String>)null);
			
			String pluginClass = pluginProps.getString("viewer.servlet.class");
			if (pluginClass == null) {
				logger.error("Viewer class is not set.");
			}
			else {
				logger.error("Plugin class " + pluginClass);
			}
			
			URLClassLoader urlClassLoader = null;
			File libDir = new File(pluginDir, "lib");
			if (libDir.exists() && libDir.isDirectory()) {
				File[] files = libDir.listFiles();
				
				ArrayList<URL> urls = new ArrayList<URL>();
				for (int i=0; i < files.length; ++i) {
					try {
						URL url = files[i].toURI().toURL();
						urls.add(url);
					} catch (MalformedURLException e) {
						logger.error(e);
					}
				}
				if (extLibClasspath != null) {
					for (String extLib : extLibClasspath) {
						try {
							File file = new File(pluginDir, extLib);
							URL url = file.toURI().toURL();
							urls.add(url);
						} catch (MalformedURLException e) {
							logger.error(e);
						}
					}
				}
				
				urlClassLoader = new URLClassLoader(urls.toArray(new URL[urls.size()]), parentLoader);
			}
			else {
				logger.error("Library path " + propertiesDir + " not found.");
				continue;
			}
			
			Class<?> viewerClass = null;
			try {
				viewerClass = urlClassLoader.loadClass(pluginClass);
			}
			catch (ClassNotFoundException e) {
				logger.error("Class " + pluginClass + " not found.");
				continue;
			}

			String source = FileIOUtils.getSourcePathFromClass(viewerClass);
			logger.info("Source jar " + source);
			jarPaths.add("jar:file:" + source);
			
			Constructor<?> constructor = null;
			try {
				constructor = viewerClass.getConstructor(Props.class);
			} catch (NoSuchMethodException e) {
				logger.error("Constructor not found in " + pluginClass);
				continue;
			}
			
			Object obj = null;
			try {
				obj = constructor.newInstance(pluginProps);
			} catch (Exception e) {
				logger.error(e);
			} 
			
			if (!(obj instanceof AbstractAzkabanServlet)) {
				logger.error("The object is not an AbstractViewerServlet");
				continue;
			}
			
			AbstractAzkabanServlet avServlet = (AbstractAzkabanServlet)obj;
			root.addServlet(new ServletHolder(avServlet), "/" + pluginWebPath + "/*");
			installedViewerPlugins.add(new ViewerPlugin(pluginName, pluginWebPath, pluginOrder, pluginHidden));
		}
		
		// Velocity needs the jar resource paths to be set.
		String jarResourcePath = StringUtils.join(jarPaths, ", ");
		logger.info("Setting jar resource path " + jarResourcePath);
		ve.addProperty("jar.resource.loader.path", jarResourcePath);
		
		// Sort plugins based on order
		Collections.sort(installedViewerPlugins, new Comparator<ViewerPlugin>() {
			@Override
			public int compare(ViewerPlugin o1, ViewerPlugin o2) {
				return o1.getOrder() - o2.getOrder();
			}
		});
		
		return installedViewerPlugins;
	}
	
	public List<ViewerPlugin> getViewerPlugins() {
		return viewerPlugins;
	}
	
	/**
	 * Loads the Azkaban property file from the AZKABAN_HOME conf directory
	 * 
	 * @return
	 */
	private static Props loadConfigurationFromAzkabanHome() {
		String azkabanHome = System.getenv("AZKABAN_HOME");

		if (azkabanHome == null) {
			logger.error("AZKABAN_HOME not set. Will try default.");
			return null;
		}

		if (!new File(azkabanHome).isDirectory() || !new File(azkabanHome).canRead()) {
			logger.error(azkabanHome + " is not a readable directory.");
			return null;
		}

		File confPath = new File(azkabanHome, DEFAULT_CONF_PATH);
		if (!confPath.exists() || !confPath.isDirectory()
				|| !confPath.canRead()) {
			logger.error(azkabanHome + " does not contain a readable conf directory.");
			return null;
		}

		return loadAzkabanConfigurationFromDirectory(confPath);
	}

	/**
	 * Returns the set temp dir
	 * 
	 * @return
	 */
	public File getTempDirectory() {
		return tempDir;
	}
	
	private static Props loadAzkabanConfigurationFromDirectory(File dir) {
		File azkabanPrivatePropsFile = new File(dir, AZKABAN_PRIVATE_PROPERTIES_FILE);
		File azkabanPropsFile = new File(dir, AZKABAN_PROPERTIES_FILE);
		
		Props props = null;
		try {
			// This is purely optional
			if (azkabanPrivatePropsFile.exists() && azkabanPrivatePropsFile.isFile()) {
				logger.info("Loading azkaban private properties file" );
				props = new Props(null, azkabanPrivatePropsFile);
			}

			if (azkabanPropsFile.exists() && azkabanPropsFile.isFile()) {
				logger.info("Loading azkaban properties file" );
				props = new Props(props, azkabanPropsFile);
			}
		} catch (FileNotFoundException e) {
			logger.error("File not found. Could not load azkaban config file", e);
		} catch (IOException e) {
			logger.error("File found, but error reading. Could not load azkaban config file", e);
		}
		
		return props;
	}

	private void configureMBeanServer() {
		logger.info("Registering MBeans...");
		mbeanServer = ManagementFactory.getPlatformMBeanServer();

		registerMbean("jetty", new JmxJettyServer(server));
		registerMbean("scheduler", new JmxScheduler(scheduleManager));
		registerMbean("slaManager", new JmxSLAManager(slaManager));
		registerMbean("executorManager", new JmxExecutorManager(executorManager));
	}
	
	public void close() {
		try {
			for (ObjectName name : registeredMBeans) {
				mbeanServer.unregisterMBean(name);
				logger.info("Jmx MBean " + name.getCanonicalName() + " unregistered.");
			}
		} catch (Exception e) {
			logger.error("Failed to cleanup MBeanServer", e);
		}
		scheduleManager.shutdown();
		slaManager.shutdown();
		executorManager.shutdown();
	}
	
	private void registerMbean(String name, Object mbean) {
		Class<?> mbeanClass = mbean.getClass();
		ObjectName mbeanName;
		try {
			mbeanName = new ObjectName(mbeanClass.getName() + ":name=" + name);
			mbeanServer.registerMBean(mbean, mbeanName);
			logger.info("Bean " + mbeanClass.getCanonicalName() + " registered.");
			registeredMBeans.add(mbeanName);
		} catch (Exception e) {
			logger.error("Error registering mbean " + mbeanClass.getCanonicalName(), e);
		}
	}
	
	public List<ObjectName> getMbeanNames() {
		return registeredMBeans;
	}
	
	public MBeanInfo getMBeanInfo(ObjectName name) {
		try {
			return mbeanServer.getMBeanInfo(name);
		} catch (Exception e) {
			logger.error(e);
			return null;
		}
	}
	
	public Object getMBeanAttribute(ObjectName name, String attribute) {
		 try {
			return mbeanServer.getAttribute(name, attribute);
		} catch (Exception e) {
			logger.error(e);
			return null;
		}
	}
}
