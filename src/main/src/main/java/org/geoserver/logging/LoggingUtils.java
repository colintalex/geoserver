/* (c) 2014 - 2016 Open Source Geospatial Foundation - all rights reserved
 * (c) 2001 - 2013 OpenPlans
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.logging;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.servlet.ServletContext;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.geoserver.config.LoggingInfo;
import org.geoserver.platform.GeoServerExtensions;
import org.geoserver.platform.GeoServerResourceLoader;
import org.geoserver.platform.resource.Paths;
import org.geoserver.platform.resource.Resource;
import org.geoserver.platform.resource.Resource.Type;
import org.geoserver.platform.resource.Resources;
import org.vfny.geoserver.global.ConfigurationException;

/**
 * Used to manage GeoServer logging facilities.
 *
 * <p>GeoTools logging is configured following {@link GeoToolsLoggingRedirection} policy, mapping
 * {@link #GT2_LOGGING_REDIRECTION} policy to appropriate LoggingFactory responsible for routing
 * java util logging api.
 *
 * <p>Use {@code -DGT2_LOGGING_REDIRECTION=Log4j * -DRELINQUISH_LOG4J_CONTROL=true
 * -Dlog4j.configuration=log4j.properties} to * redirect GeoServer to * use LOG4J API with log4j.xml
 * configuration.
 *
 * <p>Prior to GeoSerer 2.21 the LOG4J library was included as the application default, to maintain
 * an older data directory use {@code -DGT2_LOGGING_REDIRECTION=Log4j2
 * -DRELINQUISH_LOG4J_CONTROL=true -Dlog4j.configuration=DEFAULT_LOGGING.properties}. This forces
 * GeoServer to read your {@code DEFAULT_LOGGING.properties} Log4J 1.2 configuration (via Log4J
 * bridge).
 *
 * <p>To troubleshoot use {@code org.apache.logging.log4j.simplelog.StatusLogger.level=DEBUG}, or
 * adjust {@code log4j2.xml} file &lt;Configuration status=&quote;trace&quote;&gt;.
 *
 * @see org.geoserver.config.LoggingInfo
 */
public class LoggingUtils {

    /**
     * Property used to disable {@link LoggingInfo#getLevel()}, when managing logging configuration
     * manually.
     *
     * <p>Successful use of this setting requires selection {@link #GT2_LOGGING_REDIRECTION} policy,
     * and logging configuration.
     */
    public static final String RELINQUISH_LOG4J_CONTROL = "RELINQUISH_LOG4J_CONTROL";

    // public static String loggingLocation;

    /** Value of loggingRedirection established by {@link LoggingStartupContextListener}. */
    static boolean relinquishLog4jControl = false;

    /**
     * Property used override Log4J default, and force use of another logging framework.
     *
     * <p>Successful use of this setting requires use of {@link #RELINQUISH_LOG4J_CONTROL}, and a
     * custom WAR with the logging framework jars used. Use {@code
     * -DGT2_LOGGING_REDIRECTION=Logback} {@code -DRELINQUISH_LOG4J_CONTROL=true} {@code
     * -Dlog4j.configuration=logback.xml} to redirect GeoServer to use SLF4J API with logback.xml
     * configuration.
     */
    public static final String GT2_LOGGING_REDIRECTION = "GT2_LOGGING_REDIRECTION";

    /** Flag used to override {@link LoggingInfo#getLocation()} */
    public static final String GEOSERVER_LOG_LOCATION = "GEOSERVER_LOG_LOCATION";

    /** Policy settings for {@link LoggingUtils#GEOSERVER_LOG_LOCATION} configuration. */
    public static enum GeoToolsLoggingRedirection {
        Logback,
        JavaLogging,
        CommonsLogging,
        Log4J,
        Log4J2;

        /**
         * Returns the enum value corresponding to the name (using case insensitive comparison) or
         * Log4J2 if no match is found.
         */
        public static GeoToolsLoggingRedirection findValue(String name) {
            for (GeoToolsLoggingRedirection value : values()) {
                if (value.name().equalsIgnoreCase(name)) return value;
            }
            return Log4J2;
        }
    }

    /** Built-in logging configurations. */
    public static final String[] STANDARD_LOGGING_CONFIGURATIONS = {
        "DEFAULT_LOGGING",
        "GEOSERVER_DEVELOPER_LOGGING",
        "GEOTOOLS_DEVELOPER_LOGGING",
        "PRODUCTION_LOGGING",
        "QUIET_LOGGING",
        "TEST_LOGGING",
        "VERBOSE_LOGGING",
    };

    /** Default logging if configResource unavailable */
    public static void configureGeoServerLogging(String level) {}

    /**
     * Reconfigures GeoServer logging using the provided loggingConfigStream, which is interpreted
     * as a property file or xml.
     *
     * <p>A number of overrides are provided to postprocess indicated configuration.
     *
     * @param loader GeoServerResource loader used to access directory for logFileName
     * @param configResource Logging configuration (to be read as property file or xml)
     * @param suppressStdOutLogging Flag requesting that standard output logging be suppressed
     * @param suppressFileLogging Flag indicating that file output should be suppressed
     * @param logFileName Logfile name override (replacing geoserver.log default)
     * @throws FileNotFoundException
     * @throws IOException
     * @throws ConfigurationException
     */
    public static void configureGeoServerLogging(
            GeoServerResourceLoader loader,
            Resource configResource,
            boolean suppressStdOutLogging,
            boolean suppressFileLogging,
            String logFileName)
            throws FileNotFoundException, IOException, ConfigurationException {

        // JD: before we wipe out the logging configuration, save any appenders that are not
        // console or file based. This allows for other types of appenders to remain intact
        // when geoserver is reloaded (for example a test appender)
        List<Appender> savedAppenders = new ArrayList<>();
        {
            @SuppressWarnings({
                "resource",
                "PMD.CloseResource"
            }) // current context, no need to enforce AutoClosable
            LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
            Configuration configuration = loggerContext.getConfiguration();

            for (Appender check : configuration.getAppenders().values()) {
                if (check instanceof RollingFileAppender) {
                    continue;
                }
                if (check instanceof org.apache.logging.log4j.core.appender.FileAppender) {
                    continue;
                }
                if (check instanceof org.apache.logging.log4j.core.appender.ConsoleAppender) {
                    continue;
                }
                savedAppenders.add(check);
            }
        }

        // look up log file location
        if (logFileName == null) {
            logFileName = loader.get("logs").get("geoserver.log").file().getAbsolutePath();
        } else {
            if (!new File(logFileName).isAbsolute()) {
                logFileName = new File(loader.getBaseDirectory(), logFileName).getAbsolutePath();
            }
        }

        boolean successfulConfiguration = false;

        // first try log4j configuration
        successfulConfiguration =
                configureFromLog4j2(
                        configResource, suppressFileLogging, suppressStdOutLogging, logFileName);

        if (!successfulConfiguration
                && "properties".equals(Paths.extension(configResource.path()))) {
            // fallback to log4j 1.2 configuration
            successfulConfiguration =
                    configureFromLog4jProperties(
                            configResource,
                            suppressFileLogging,
                            suppressStdOutLogging,
                            logFileName);
        }

        if (!successfulConfiguration) {
            configureDefault(Level.INFO);
            LoggingStartupContextListener.getLogger()
                    .log(
                            Level.WARNING,
                            "Could setup Log4J using configuration file '"
                                    + configResource.name()
                                    + "'."
                                    + "Both Log4J 2 and Log4j 1.2 configuration formats were attempted. To troubleshoot"
                                    + "configuration setup use ");
            return;
        }

        // Check configuration via Log4J 2 API
        boolean reloadRequired =
                checkConfiguration(suppressStdOutLogging, suppressFileLogging, logFileName);

        // add the appenders we saved above (for example a test appender)
        {
            @SuppressWarnings({
                "resource",
                "PMD.CloseResource"
            }) // current context, no need to enforce AutoClosable
            LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
            Configuration configuration = loggerContext.getConfiguration();

            for (Appender appender : savedAppenders) {
                configuration.addAppender(appender);
                reloadRequired = true;
            }
            if (reloadRequired) {
                // we modifed the log4j configuration and should reload
                loggerContext.reconfigure(configuration);
                // loggerContext.updateLoggers();
            }
        }

        LoggingStartupContextListener.getLogger()
                .fine("FINISHED CONFIGURING GEOSERVER LOGGING -------------------------");
    }

    /**
     * Double check configuration respects suppressFileLogging and logFileName.
     *
     * @param suppressStdOutLogging
     * @param suppressFileLogging
     * @param logFileName
     * @return
     */
    static boolean checkConfiguration(
            boolean suppressStdOutLogging, boolean suppressFileLogging, String logFileName) {

        @SuppressWarnings({
            "resource",
            "PMD.CloseResource"
        }) // current context, no need to enforce AutoClosable
        LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);

        Configuration configuration = loggerContext.getConfiguration();

        boolean reloadRequired = false;

        if (!suppressFileLogging) {
            if (configuration.getProperties().containsKey("GEOSERVER_LOG_LOCATION")) {
                // this is a log4j 2 configuration using default properties
                LoggingStartupContextListener.getLogger()
                        .fine(
                                "Logging property GEOSERVER_LOG_LOCATION set to file '"
                                        + logFileName
                                        + "' for use by appenders.");
            }

            // check resulting configuring of log4j file logger
            Appender gslf = configuration.getAppender("geoserverlogfile");
            if (gslf instanceof RollingFileAppender) {
                RollingFileAppender fileAppender = (RollingFileAppender) gslf;
                if (logFileName.equals(fileAppender.getFileName())) {
                    LoggingStartupContextListener.getLogger()
                            .fine("Logging output set to file '" + logFileName + "'");
                } else {
                    LoggingStartupContextListener.getLogger()
                            .fine(
                                    "Logging output to file '"
                                            + fileAppender.getFileName()
                                            + "', ignored '"
                                            + logFileName
                                            + "'");
                }
            } else if (gslf instanceof FileAppender) {
                FileAppender fileAppender = (FileAppender) gslf;
                if (logFileName.equals(fileAppender.getFileName())) {
                    LoggingStartupContextListener.getLogger()
                            .fine("Logging output set to file '" + logFileName + "'");
                } else {
                    LoggingStartupContextListener.getLogger()
                            .fine(
                                    "Logging output to file '"
                                            + fileAppender.getFileName()
                                            + "', ignored '"
                                            + logFileName
                                            + "'");
                }
            } else if (gslf != null) {
                LoggingStartupContextListener.getLogger()
                        .warning(
                                "'log4j.appender.geoserverlogfile' appender is defined, but isn't a FileAppender.  GeoServer won't control the file-based logging.");
            }
        } else {
            LoggingStartupContextListener.getLogger()
                    .info(
                            "Suppressing file logging, if you want to see GeoServer logs, be sure to look in stdOut");
            for (Appender check : configuration.getAppenders().values()) {
                if (check instanceof FileAppender || check instanceof RollingFileAppender) {
                    LoggingStartupContextListener.getLogger()
                            .warning(
                                    "'"
                                            + check.getName()
                                            + "' appender is defined, but GeoServer asked that file logging be supressed.");
                }
            }
        }

        // ... and the std output logging too
        if (suppressStdOutLogging) {
            LoggingStartupContextListener.getLogger()
                    .info(
                            "Suppressing StdOut logging.  If you want to see GeoServer logs, be sure to look in '"
                                    + logFileName
                                    + "'");
            for (Appender check : configuration.getAppenders().values()) {
                if (check instanceof ConsoleAppender) {
                    configuration.getAppenders().values().remove(check);
                    reloadRequired = true;
                }
            }
        }
        return reloadRequired;
    }

    /**
     * Configure via Log4J API.
     *
     * <p>While post-processing generating configuration can detect and fix most problems we can
     * take the time to respect:
     *
     * <ul>
     *   <li>suppressFileLogging: When {@code true}, remove {@code log4j.appender.geoserverlogfile}
     *   <li>logFilename: added as {@code log4j.appender.geoserverlogfile.File}
     * </ul>
     *
     * This preserves GeoServer 2.20.x Log4J 1.2 API workflow.
     *
     * @param configResource log4j properties file
     * @param noFileLogging True to disable and/or remove all file appenders
     * @param noConsoleLogging True to disable and/or remove all console appenders
     * @param logFileName Logfile output location
     * @return true for successful configuration
     */
    static boolean configureFromLog4j2(
            Resource configResource,
            final boolean noFileLogging,
            final boolean noConsoleLogging,
            final String logFileName) {

        String extension = Paths.extension(configResource.path());

        @SuppressWarnings({
            "resource",
            "PMD.CloseResource"
        }) // current context, no need to enforce AutoClosable
        LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);

        try {
            URI configLocation = Resources.file(configResource).toURI();

            // Use log4j 1.2 API to reset
            // org.apache.log4j.LogManager.resetConfiguration();

            // And configure from new location
            loggerContext.setName(configResource.name());

            if (extension.equalsIgnoreCase("xml")) {
                ConfigurationSource source = ConfigurationSource.fromUri(configLocation);
                GeoServerXMLConfiguration configuration =
                        new GeoServerXMLConfiguration(loggerContext, source) {
                            @Override
                            public void setup() {
                                this.loggingLocation = logFileName;
                                this.suppressFileLogging = noFileLogging;
                                this.suppressStdOutLogging = noConsoleLogging;
                                super.setup();
                            }
                        };

                loggerContext.reconfigure(configuration);

                Logger LOGGER = LoggingStartupContextListener.getLogger();
                LOGGER.info("Log4j 2 configuration set to " + configResource.name());
                return true;
            } else {
                loggerContext.setConfigLocation(configLocation);
                LoggingStartupContextListener.LOGGER.info(
                        "Log4j 2 configuration set to " + configResource.name());
                return true;
            }
        } catch (Throwable unsuccessful) {
            LoggingStartupContextListener.getLogger()
                    .log(
                            Level.WARNING,
                            "Could not access Log4J 2 configuration uri '"
                                    + configResource.name()
                                    + "'",
                            unsuccessful);
        }
        return false;
    }
    /**
     * Configure via Log4J 1.2 API from properties file.
     *
     * <p>While post-processing generating configuration can detect and fix most problems we can
     * take the time to respect:
     *
     * <ul>
     *   <li>suppressFileLogging: When {@code true}, remove {@code log4j.appender.geoserverlogfile}
     *   <li>logFilename: added as {@code log4j.appender.geoserverlogfile.File}
     * </ul>
     *
     * This preserves GeoServer 2.20.x Log4J 1.2 API workflow.
     *
     * @param configResource log4j properties file
     * @param suppressFileLogging True to disable and/or remove all file appenders
     * @param suppressStdOutLogging True to disable and/or remove all console appenders
     * @param logFileName Logfile output location
     * @return true for successful configuration
     */
    static boolean configureFromLog4jProperties(
            Resource configResource,
            boolean suppressFileLogging,
            boolean suppressStdOutLogging,
            String logFileName) {

        Properties lprops = new Properties();

        try (InputStream loggingConfigStream = configResource.in()) {
            if (loggingConfigStream == null) {
                LoggingStartupContextListener.getLogger()
                        .log(
                                Level.WARNING,
                                "Could not access Log4J 1.2 configuration file '"
                                        + configResource.name()
                                        + "'");
                return false;
            } else {
                LoggingStartupContextListener.getLogger()
                        .fine("GeoServer logging profile '" + configResource.name() + "' enabled.");
            }
            lprops.load(loggingConfigStream);
        } catch (IOException couldNotRead) {
            LoggingStartupContextListener.getLogger()
                    .log(
                            Level.WARNING,
                            "Could not access Log4J 1.2 configuration file '"
                                    + configResource.name()
                                    + "'");
            return false;
        }

        if (suppressFileLogging) {
            List<String> removeAppender =
                    lprops.keySet().stream()
                            .map(k -> (String) k)
                            .filter(k -> ((String) k).startsWith("log4j.appender.geoserverlogfile"))
                            .collect(Collectors.toList());

            lprops.keySet().removeAll(removeAppender);
        } else {
            // Add the file location to log4j configuration if needed
            if (lprops.containsKey("log4j.appender.geoserverlogfile")) {
                String appenderClass = (String) lprops.get("log4j.appender.geoserverlogfile");
                if (appenderClass.endsWith("FileAppender")) {
                    lprops.setProperty("log4j.appender.geoserverlogfile.File", logFileName);
                }
            }
        }

        // Configure via Log4J 1.2 API
        try {
            org.apache.log4j.LogManager.resetConfiguration();
            org.apache.log4j.PropertyConfigurator.configure(lprops);
            return true;
        } catch (Throwable unsuccessful) {
            LoggingStartupContextListener.getLogger()
                    .log(
                            Level.WARNING,
                            "Could not access Log4J 1.2 configuration file '"
                                    + configResource.name()
                                    + "'",
                            unsuccessful);
            return false;
        }
    }

    /**
     * Fallback configuring log4j with default configuration.
     *
     * @param level Level used to set system property "org.apache.logging.log4j.level"
     * @return true for successful configuration
     */
    static boolean configureDefault(Level level) {
        int value = level.intValue();
        String defaultLevel;
        if (value > Level.SEVERE.intValue()) {
            defaultLevel = "OFF";
        } else if (value > Level.WARNING.intValue()) {
            defaultLevel = "ERROR";
        } else if (value > Level.INFO.intValue()) {
            defaultLevel = "WARN";
        } else if (value > Level.FINE.intValue()) {
            defaultLevel = "INFO";
        } else if (value > Level.FINER.intValue()) {
            defaultLevel = "DEBUG";
        } else if (value > Level.FINEST.intValue()) {
            defaultLevel = "TRACE";
        } else {
            defaultLevel = "ALL";
        }
        System.setProperty(DefaultConfiguration.DEFAULT_LEVEL, defaultLevel);

        @SuppressWarnings({
            "resource",
            "PMD.CloseResource"
        }) // current context, no need to enforce AutoClosable
        LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
        loggerContext.reconfigure(new DefaultConfiguration());

        return true;
    }

    /**
     * Initial setup of the logging system and handling of built-in configuration.
     *
     * <p>A number of overrides are provided to postprocess indicated configuration.
     *
     * @param resourceLoader GeoServerResource loader used to access directory for logFileName
     * @param configFileName Logging configuration filename (default is "DEFAULT_LOGGING");
     * @param suppressStdOutLogging Flag requesting that standard output logging be * suppressed
     *     param boolean suppressFileLogging
     * @param logFileName Logfile name override (replacing geoserver.log default)
     * @throws Exception
     */
    public static void initLogging(
            GeoServerResourceLoader resourceLoader,
            String configFileName,
            boolean suppressStdOutLogging,
            boolean suppressFileLogging,
            String logFileName)
            throws Exception {

        // to initialize logging we need to do a couple of things:
        // 1)  Figure out whether the user has 'overridden' some configuration settings
        // in the logging system (not using log4j in commons-logging.properties or perhaps
        // has set up their own 'custom' log4j.properties file.
        // 2)  If they *have*, then we don't worry about configuring logging
        // 3)  If they haven't, then we configure logging to use the log4j config file
        // specified, and remove console appenders if the suppressstdoutlogging is true.
        LoggingStartupContextListener.getLogger()
                .fine("CONFIGURING GEOSERVER LOGGING -------------------------");

        // ensure standard logging configuration files are in place (although they may be
        // customized)
        checkStandardLoggingConfiguration(resourceLoader);
        if (configFileName == null) {
            configFileName = "DEFAULT_LOGGING";
            LoggingStartupContextListener.getLogger()
                    .config(
                            "No logging configuration (the logging.xml level) defined:  using 'DEFAULT_LOGGING'");
        }

        Resource configResource = determineLoggingProfile(resourceLoader, configFileName);
        if (configResource.getType() != Type.RESOURCE) {
            configureDefault(Level.INFO);
            LoggingStartupContextListener.getLogger()
                    .warning(
                            "Unable to load logging configuration '"
                                    + configFileName
                                    + "'.  In addition, an attempt was made to create the 'logs' directory in your data dir, "
                                    + "and to use the DEFAULT_LOGGING configuration, but this failed as well. "
                                    + "Is your data dir writeable?");
            return;
        }

        // reconfiguring log4j logger levels by resetting and loading a new configuration
        configureGeoServerLogging(
                resourceLoader,
                configResource,
                suppressStdOutLogging,
                suppressFileLogging,
                logFileName);
    }

    /**
     * Determine appropriate configuration resource from provided configFileName
     *
     * @param resourceLoader
     * @param configFileName
     * @return config resource, or null if not found
     */
    static Resource determineLoggingProfile(
            GeoServerResourceLoader resourceLoader, String configFileName) {
        Resource logs = resourceLoader.get("logs");

        Resource configResource = logs.get(configFileName);
        if (configResource.getType() != Type.RESOURCE) {

            final String[] EXTENSIONS = {"xml", "yml", "yaml", "json", "jsn", "properties"};

            Map<String, Resource> availableLoggingConfigurations =
                    logs.list().stream()
                            .filter(
                                    r ->
                                            r.getType() == Type.RESOURCE
                                                    && r.name().contains("_LOGGING"))
                            .collect(Collectors.toMap(r -> r.name(), r -> r));

            String baseFileName =
                    configFileName.lastIndexOf('.') == -1
                            ? configFileName
                            : configFileName.substring(0, configFileName.lastIndexOf('.'));

            for (String extension : EXTENSIONS) {
                if (availableLoggingConfigurations.containsKey(baseFileName + "." + extension)) {
                    configResource =
                            availableLoggingConfigurations.get(baseFileName + "." + extension);
                    break;
                }
            }
        }
        return configResource;
    }

    /**
     * Upgrade standard logging configurations to match built-in class resources.
     *
     * <p>This method will check each LOGGING profile against the internal templates and unpack any
     * xml configurations that are missing, and remove any log4j properties configurations.
     */
    static void checkStandardLoggingConfiguration(GeoServerResourceLoader resourceLoader) {
        Resource logs = resourceLoader.get("logs");
        File logsDirectory = logs.dir();

        for (String logConfigFile : STANDARD_LOGGING_CONFIGURATIONS) {
            String logConfigXml = logConfigFile + ".xml";
            String logConfigProperties = logConfigFile + ".properties";

            File target = new File(logsDirectory.getAbsolutePath(), logConfigXml);
            File properties = new File(logsDirectory.getAbsolutePath(), logConfigProperties);

            if (properties.exists()) {
                boolean deleted = properties.delete();
                if (deleted) {
                    LoggingStartupContextListener.getLogger()
                            .finer(
                                    "Check '"
                                            + logConfigProperties
                                            + "' logging configuration - outdated and removed");
                } else {
                    LoggingStartupContextListener.getLogger()
                            .config(
                                    "Check '"
                                            + logConfigProperties
                                            + "' logging configuration - outdated and unable to remove. Is your data dir writeable?");
                }
            }
            if (target.exists()) {
                try (FileInputStream targetContents = new FileInputStream(target);
                        InputStream template = getStreamFromResource(logConfigXml)) {
                    if (!IOUtils.contentEquals(targetContents, template)) {
                        LoggingStartupContextListener.getLogger()
                                .finer(
                                        "Check '"
                                                + logConfigXml
                                                + "' logging configuration, outdated");
                        resourceLoader.copyFromClassPath(logConfigXml, target);
                    }
                } catch (IOException e) {
                    LoggingStartupContextListener.getLogger()
                            .log(
                                    Level.WARNING,
                                    "Check '"
                                            + logConfigXml
                                            + "' logging configuration - unable to check against template",
                                    e);
                }
            } else {
                try {
                    resourceLoader.copyFromClassPath(logConfigXml, target);
                } catch (IOException e) {
                    LoggingStartupContextListener.getLogger()
                            .config(
                                    "Check '"
                                            + logConfigXml
                                            + "' logging configuration - unable to create. Is your data dir writeable?");
                }
            }
        }
    }

    /**
     * Used to access internal logging template contents.
     *
     * @param classpathResource Example "DEFAULT_LOGGING.xml"
     * @return inputstream for resource contents
     * @throws IOException If the resource could not be found.
     */
    private static InputStream getStreamFromResource(String classpathResource) throws IOException {
        InputStream is = null;
        is = Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathResource);
        if (is == null) {
            throw new IOException(
                    "Could not obtain "
                            + classpathResource
                            + " from scope "
                            + Thread.currentThread().getContextClassLoader().toString()
                            + ".");
        }
        return is;
    }

    /**
     * Finds the log location in the "context" (system variable, env variable, servlet context) or
     * uses the provided base location otherwise
     */
    public static String getLogFileLocation(String baseLocation) {
        return getLogFileLocation(baseLocation, null);
    }

    /**
     * Finds the log location in the "context" (system variable, env variable, servlet context) or
     * uses the provided base location otherwise.
     *
     * <p>This method accepts a servlet context directly for cases where the logging location must
     * be known but the spring application context may not be initialized yet.
     */
    public static String getLogFileLocation(String baseLocation, ServletContext context) {
        // accept a servlet context directly in the case of startup where the application context
        // is not yet available, in other cases (like a logging change) we can fall back on the
        // app context and derive the servlet context from that
        String location =
                context != null
                        ? GeoServerExtensions.getProperty(
                                LoggingUtils.GEOSERVER_LOG_LOCATION, context)
                        : GeoServerExtensions.getProperty(LoggingUtils.GEOSERVER_LOG_LOCATION);
        if (location == null) {
            return baseLocation;
        } else {
            return location;
        }
    }
}
