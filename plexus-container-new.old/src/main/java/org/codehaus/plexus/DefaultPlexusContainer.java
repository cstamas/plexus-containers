package org.codehaus.plexus;

import org.apache.avalon.framework.configuration.Configuration;
import org.codehaus.classworlds.ClassWorld;
import org.codehaus.classworlds.NoSuchRealmException;
import org.codehaus.plexus.classloader.DefaultResourceManager;
import org.codehaus.plexus.classloader.ResourceManagerFactory;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.component.repository.ComponentRepository;
import org.codehaus.plexus.component.repository.ComponentRepositoryFactory;
import org.codehaus.plexus.configuration.ConfigurationMerger;
import org.codehaus.plexus.configuration.ConfigurationResourceException;
import org.codehaus.plexus.configuration.DefaultConfiguration;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.configuration.builder.XmlPullConfigurationBuilder;
import org.codehaus.plexus.context.ContextMapAdapter;
import org.codehaus.plexus.context.DefaultContext;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.logging.LoggerManager;
import org.codehaus.plexus.logging.LoggerManagerFactory;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.InterpolationFilterReader;

import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Map;

/** The main Plexus container component.
 *
 *  @author <a href="mailto:jason@zenplex.com">Jason van Zyl</a>
 *  @author <a href="mailto:bob@eng.werken.com">bob mcwhirter</a>
 *
 *  @version $Id$
 *
 *  @todo Make ClassWorlds optional so we can make the runtime tiny.
 *  @todo the container itself must be able to behave like a normal
 *        component so that we can deal with hierachies. In the majority of
 *        cases the derived container will take a lot of configuration information
 *        from the parent.
 */
public class DefaultPlexusContainer
    extends AbstractLogEnabled
    implements PlexusContainer
{
    // ----------------------------------------------------------------------
    //  Instance Members
    // ----------------------------------------------------------------------

    /** Logger Manager used for this container. */
    private LoggerManager loggerManager;

    /** Context used for this container. */
    private DefaultContext context;

    /** Service Repository used for this container. */
    private ComponentRepository componentRepository;

    /** Configuration for this container. */
    private PlexusConfiguration configuration;

    /** Default configuration. */
    private PlexusConfiguration defaultConfiguration;

    /** The configuration resource. */
    private Reader configurationReader;

    /**
     *  Typically Plexus will use a ClassWorld for all its class loading and
     *  resource requirements, but it remains to be seen if this will be possible
     *  in environments like j2me. We need to be able to initialize the Plexus
     *  resource manager with either a class world or a standard class loader.
     */
    private ClassWorld classWorld;

    /** Class loader used for this container if a class world is not available. */
    private ClassLoader classLoader;

    /**
     *  Resource manager for this container. It is available via the context using
     *  plexus:resource-manager key.
     */
    private DefaultResourceManager resourceManager;

    /** Default Configuration Builder. */
    private XmlPullConfigurationBuilder builder;

    /** XML element used to start the logging configuration block. */
    public static final String LOGGING_TAG = "logging";

    // ----------------------------------------------------------------------
    //  Constructors
    // ----------------------------------------------------------------------

    /**
     *  Constuct.
     */
    public DefaultPlexusContainer()
    {
        builder = new XmlPullConfigurationBuilder();
    }

    // ----------------------------------------------------------------------
    // Container Contract
    // ----------------------------------------------------------------------

    public Object lookup( String componentKey )
        throws ComponentLookupException
    {
        return componentRepository.lookup( componentKey );
    }

    public Map lookupAll( String role )
        throws ComponentLookupException
    {
        return componentRepository.lookupAll( role );
    }

    public Object lookup( String role, String id )
        throws ComponentLookupException
    {
        return componentRepository.lookup( role, id );
    }

    public boolean hasService( String componentKey )
    {
        return componentRepository.hasService( componentKey );
    }

    public boolean hasService( String role, String id )
    {
        return componentRepository.hasService( role, id );
    }

    public void release( Object component )
    {
        componentRepository.release( component );
    }

    public void suspend( Object component )
    {
        componentRepository.suspend( component );
    }

    public void resume( Object component )
    {
        componentRepository.resume( component );
    }

    // ----------------------------------------------------------------------
    // Lifecylce Management
    // ----------------------------------------------------------------------

    /**
     * - Initialize ClassLoader
     * - Initialize the default configuration
     * - Initialize the configuration
     * - Initialize logger manager
     * - Initialize component repository
     * - Initialize resource manager
     * - Initialize the context. Values put into the context at this point won't
     *   be interpolated into the configuration.  This may need to change later.
     * - Initialize lifecycle handler
     *
     * @throws Exception
     */
    public void initialize()
        throws Exception
    {
        initializeClassLoader();
        initializeDefaultConfiguration();
        initializeConfiguration();
        initializeLoggerManager();
        initializeComponentRepository();
        initializeResourceManager();
        initializeContext();
        initializeSystemProperties();
    }

    public void start()
        throws Exception
    {
        loadOnStart();
    }

    public void dispose()
        throws Exception
    {
        componentRepository.dispose();
    }

    // ----------------------------------------------------------------------
    // Pre-initialization - can only be called prior to initialization
    // ----------------------------------------------------------------------

    public void addContextValue( Object key, Object value )
    {
        getContext().put( key, value );
    }

    public void setClassLoader( ClassLoader classLoader )
    {
        this.classLoader = classLoader;
    }

    public void setClassWorld( ClassWorld classWorld )
    {
        this.classWorld = classWorld;
    }

    /** @see PlexusContainer#setConfigurationResource(Reader) */
    public void setConfigurationResource( Reader configuration )
        throws ConfigurationResourceException
    {
        this.configurationReader = configuration;
    }

    // ----------------------------------------------------------------------
    // Post-initialization - can only be called post initialization
    // ----------------------------------------------------------------------

    public ClassLoader getClassLoader()
    {
        if ( classLoader == null )
        {
            throw new IllegalStateException( "This container must be assigned a ClassLoader." );
        }

        return classLoader;
    }

    // ----------------------------------------------------------------------
    // Implementation
    // ----------------------------------------------------------------------

    /**
     *  Load specifies roles during server startup.
     */
    protected void loadOnStart()
        throws Exception
    {
        Configuration[] loadOnStartServices = configuration.getChild( "load-on-start" ).getChildren( "service" );

        for ( int i = 0; i < loadOnStartServices.length; i++ )
        {
            String role = loadOnStartServices[i].getAttribute( "role" );

            String id = loadOnStartServices[i].getAttribute( "id", "" );

            getLogger().info( "Loading on start [role,id]: " + "[" + role + "," + id + "]" );

            try
            {
                if ( id.length() == 0 )
                {
                    componentRepository.lookup( role );
                }
                else
                {
                    componentRepository.lookup( role, id );
                }
            }
            catch ( ComponentLookupException e )
            {
                getLogger().error( "Cannot load-on-start " + role, e );
            }
        }
    }

    // ----------------------------------------------------------------------
    // Initialization Implementation
    // ----------------------------------------------------------------------

    private void initializeClassLoader()
        throws Exception
    {
        if ( getClassWorld() != null )
        {
            try
            {
                classLoader = getClassWorld().getRealm( "core" ).getClassLoader();
            }
            catch ( NoSuchRealmException e )
            {
            }
        }
        else
        {
            classLoader = Thread.currentThread().getContextClassLoader();
        }
    }

    /**
     * Initialize the context.
     */
    private void initializeContext()
    {
        addContextValue( PlexusConstants.PLEXUS_KEY, this );
        addContextValue( PlexusConstants.RESOURCE_MANAGER_KEY, resourceManager );
        addContextValue( PlexusConstants.COMMON_CLASSLOADER, getClassLoader() );
    }

    /**
     * Initialize the configuration.
     *
     * @throws Exception
     */
    private void initializeDefaultConfiguration()
        throws Exception
    {
        InputStream is = getClassLoader().getResourceAsStream( "org/codehaus/plexus/plexus.conf" );

        if ( is == null )
        {
            throw new IllegalStateException( "The internal default plexus.conf is missing. " +
                                             "This is highly irregular, your plexus JAR is " +
                                             "most likely corrupt." );
        }

        setDefaultConfiguration( builder.parse( new InputStreamReader( is ) ) );
    }

    /**
     * Initialize the configuration.
     *
     * @throws Exception
     */
    private void initializeConfiguration()
        throws Exception
    {
        setConfiguration( builder.parse( getInterpolationConfigurationReader( getConfigurationReader() ) ) );

        processConfigurationsDirectory();
    }

    private Reader getInterpolationConfigurationReader( Reader reader )
    {
        InterpolationFilterReader interpolationFilterReader =
            new InterpolationFilterReader( reader, new ContextMapAdapter( getContext() ) );

        return interpolationFilterReader;
    }

    /**
     * Process any additional component configuration files that have been
     * specified. The specified directory is scanned recursively so configurations
     * can be within nested directories to help with component organization.
     */
    private void processConfigurationsDirectory()
        throws Exception
    {
        String s = getConfiguration().getChild( "configurations-directory" ).getValue( null );

        if ( s != null )
        {
            DefaultConfiguration componentsConfiguration =
                (DefaultConfiguration) getConfiguration().getChild( "components" );

            File configurationsDirectory = new File( s );

            if ( configurationsDirectory.exists()
                &&
                configurationsDirectory.isDirectory() )
            {
                DirectoryScanner scanner = new DirectoryScanner();
                scanner.setBasedir( configurationsDirectory );
                scanner.setIncludes( new String[]{"**/*.conf", "**/*.xml"} );
                scanner.scan();

                String[] confs = scanner.getIncludedFiles();

                for ( int i = 0; i < confs.length; i++ )
                {
                    File conf = new File( configurationsDirectory, confs[i] );

                    Configuration c = builder.parse( getInterpolationConfigurationReader( new FileReader( conf ) ) );

                    componentsConfiguration.addAllChildren( c.getChild( "components" ) );
                }
            }
        }
    }

    private Configuration mergedConfiguration;

    private Configuration getMergedConfiguration()
        throws Exception
    {
        if ( mergedConfiguration == null )
        {
            mergedConfiguration = ConfigurationMerger.merge( getConfiguration(), getDefaultConfiguration() );

            // A little tweak for the lifecycle handlers

            Configuration[] lifecycleHandlers = getConfiguration().getChild( "lifecycle-handlers" ).getChildren( "lifecycle-handler" );

            if ( lifecycleHandlers != null )
            {
                DefaultConfiguration defaultLifecycleHandlers =
                    (DefaultConfiguration) mergedConfiguration.getChild( "lifecycle-handler-manager" ).getChild( "lifecycle-handlers" );

                for ( int i = 0; i < lifecycleHandlers.length; i++ )
                {
                    defaultLifecycleHandlers.addChild( lifecycleHandlers[i] );
                }
            }
        }

        return mergedConfiguration;
    }

    /**
     * Initialize Logging.
     *
     * @throws Exception
     */
    private void initializeLoggerManager()
        throws Exception
    {
        LoggerManager loggerManager = LoggerManagerFactory.create( getMergedConfiguration().getChild( LOGGING_TAG ), getClassLoader() );

        enableLogging( loggerManager.getRootLogger() );

        setLoggerManager( loggerManager );
    }

    /**
     * Intialize the component repository.
     *
     * @throws Exception
     */
    private void initializeComponentRepository()
        throws Exception
    {
        ComponentRepository componentRepository =
            ComponentRepositoryFactory.create( getMergedConfiguration(),
                                               getLoggerManager(),
                                               this,
                                               getClassLoader(),
                                               getContext() );

        setComponentRepository( componentRepository );
    }

    /**
     * Initialize the resource manager.
     *
     * @throws Exception
     */
    private void initializeResourceManager()
        throws Exception
    {
        DefaultResourceManager rm =
            ResourceManagerFactory.create( getMergedConfiguration(),
                                           getLoggerManager(),
                                           getClassLoader() );

        // This needs to be completely clarified. If the container becomes the boundary
        // and barrier between all behaviour in plexus then the subsystems like classworlds
        // can't undermine the barrier. This behaviour is also dependent on composite
        // and primitive components a la SOFA.
        setResourceManager( rm );
        setClassLoader( rm.getPlexusClassLoader() );
        Thread.currentThread().setContextClassLoader( getClassLoader() );
    }

    /**
     * Initialize system properties.
     *
     * If the application needs to setup any system properties than they will
     * be initialized here.
     *
     * @throws Exception
     */
    private void initializeSystemProperties()
        throws Exception
    {
        Configuration[] systemProperties =
            getConfiguration().getChild( "system-properties" ).getChildren( "property" );

        for ( int i = 0; i < systemProperties.length; ++i )
        {
            String name = systemProperties[i].getAttribute( "name" );
            String value = systemProperties[i].getAttribute( "value" );
            System.getProperties().setProperty( name, value );

            getLogger().info( "Setting system property: [ " + name + ", " + value + " ]" );
        }
    }

    // ----------------------------------------------------------------------
    // Internal Accessors
    // ----------------------------------------------------------------------

    /**
     * Set the logger manager.
     *
     * @param loggerManager
     */
    private void setLoggerManager( LoggerManager loggerManager )
    {
        this.loggerManager = loggerManager;
    }

    /**
     * Get the logger manager.
     *
     * @return The logger manager.
     */
    private LoggerManager getLoggerManager()
    {
        return loggerManager;
    }

    /**
     *
     * @return
     */
    private PlexusConfiguration getConfiguration()
    {
        return configuration;
    }

    private void setConfiguration( PlexusConfiguration configuration )
    {
        this.configuration = configuration;
    }

    private Reader getConfigurationReader()
    {
        return configurationReader;
    }

    /**
     *
     * @param resourceManager
     */
    private void setResourceManager( DefaultResourceManager resourceManager )
    {
        this.resourceManager = resourceManager;
    }

    /**
     *
     * @return
     */
    private DefaultContext getContext()
    {
        if ( context == null )
        {
            context = new DefaultContext();
        }

        return context;
    }

    /**
     *
     * @param componentRepository
     */
    private void setComponentRepository( ComponentRepository componentRepository )
    {
        this.componentRepository = componentRepository;
    }

    /**
     *
     * @return
     */
    private PlexusConfiguration getDefaultConfiguration()
    {
        return defaultConfiguration;
    }

    /**
     *
     * @param defaultConfiguration
     */
    private void setDefaultConfiguration( PlexusConfiguration defaultConfiguration )
    {
        this.defaultConfiguration = defaultConfiguration;
    }

    /**
     *
     * @return
     */
    private ClassWorld getClassWorld()
    {
        return classWorld;
    }
}


