package org.codehaus.plexus;

/*
 * Copyright 2001-2006 Codehaus Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import static org.codehaus.plexus.PlexusConstants.PLEXUS_DEFAULT_HINT;
import static org.codehaus.plexus.component.CastUtils.cast;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.classworlds.realm.DuplicateRealmException;
import org.codehaus.plexus.classworlds.realm.NoSuchRealmException;
import org.codehaus.plexus.component.discovery.ComponentDiscoverer;
import org.codehaus.plexus.component.discovery.ComponentDiscovererManager;
import org.codehaus.plexus.component.discovery.ComponentDiscoveryListener;
import org.codehaus.plexus.component.discovery.PlexusXmlComponentDiscoverer;
import org.codehaus.plexus.component.factory.ComponentFactoryManager;
import org.codehaus.plexus.component.repository.ComponentDescriptor;
import org.codehaus.plexus.component.repository.ComponentSetDescriptor;
import org.codehaus.plexus.component.repository.ComponentDescriptorListener;
import org.codehaus.plexus.component.repository.exception.ComponentLifecycleException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.component.repository.exception.ComponentRepositoryException;
import org.codehaus.plexus.component.repository.io.PlexusTools;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.configuration.PlexusConfigurationException;
import org.codehaus.plexus.configuration.PlexusConfigurationMerger;
import org.codehaus.plexus.configuration.source.ConfigurationSource;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.codehaus.plexus.container.initialization.ContainerInitializationContext;
import org.codehaus.plexus.container.initialization.ContainerInitializationPhase;
import org.codehaus.plexus.context.Context;
import org.codehaus.plexus.context.ContextException;
import org.codehaus.plexus.context.ContextMapAdapter;
import org.codehaus.plexus.context.DefaultContext;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.LoggerManager;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.InterpolationFilterReader;
import org.codehaus.plexus.util.ReaderFactory;

/**
 * Default implementation of PlexusContainer and MutablePlexusContainer.
 * @author Jason van Zyl
 * @author Kenney Westerhof
 */
public class DefaultPlexusContainer
    extends AbstractLogEnabled
    implements MutablePlexusContainer
{
    protected static final String DEFAULT_CONTAINER_NAME = "default";

    protected static final String DEFAULT_REALM_NAME = "plexus.core";

    /**
     * Arbitrary data associated with the container.  Data in the container has highest precedence when configuring
     * a component to create.
     */
    protected Context containerContext;

    protected PlexusConfiguration configuration;

    // todo: don't use a reader
    protected Reader configurationReader;

    protected ClassWorld classWorld;

    protected ClassRealm containerRealm;

    // ----------------------------------------------------------------------------
    // Core components
    // ----------------------------------------------------------------------------

    private ComponentRegistry componentRegistry;

    /**
     * Simple index (registry) of ComponentDiscovers and ComponentDiscoveryListener.
     */
    protected ComponentDiscovererManager componentDiscovererManager;

    /**
     * Trivial class to look-up ComponentFactory instances in this container.
     */
    protected ComponentFactoryManager componentFactoryManager;

    /**
     * Generic logger interface.
     */
    protected LoggerManager loggerManager;

    /**
     * Converts a ComponentDescriptor into PlexusConfiguration.
     */
    protected ConfigurationSource configurationSource;

    // ----------------------------------------------------------------------------
    //
    // ----------------------------------------------------------------------------

    // TODO: Is there a more threadpool-friendly way to do this?
    private ThreadLocal<ClassRealm> lookupRealm = new ThreadLocal<ClassRealm>();

    public void addComponent( Object component, String role )
        throws ComponentRepositoryException
    {
        ClassRealm classRealm = null;

        // find a realm for this instance
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if ( classLoader instanceof ClassRealm )
        {
            classRealm = (ClassRealm) classLoader;
        }
        if (classRealm == null)
        {
            classRealm = getContainerRealm();
        }

        addComponent( component, getRoleClass( role ), PLEXUS_DEFAULT_HINT, classRealm );
    }

    public <T> void addComponent( T component, Class<?> type, String roleHint, ClassRealm classRealm )
        throws ComponentRepositoryException
    {
        componentRegistry.addComponent( component, type, roleHint, classRealm );
    }

    public ClassRealm setLookupRealm( ClassRealm realm )
    {
        ClassRealm oldRealm = lookupRealm.get();

        lookupRealm.set( realm );

        return oldRealm;
    }

    public ClassRealm getLookupRealm()
    {
        return lookupRealm.get();
    }

    // ----------------------------------------------------------------------
    // Constructors
    // ----------------------------------------------------------------------

    public DefaultPlexusContainer()
        throws PlexusContainerException
    {
        construct( new DefaultContainerConfiguration() );
    }

    public DefaultPlexusContainer( ContainerConfiguration c )
        throws PlexusContainerException
    {
        construct( c );
    }

    public ClassRealm createChildRealm( String id )
    {
        try
        {
            return containerRealm.createChildRealm( id );
        }
        catch ( DuplicateRealmException e )
        {
            try
            {
                return classWorld.getRealm( id );
            }
            catch ( NoSuchRealmException e1 )
            {
                return null;
            }
        }
    }

    private void construct( ContainerConfiguration c )
        throws PlexusContainerException
    {
        configurationSource = c.getConfigurationSource();

        // ----------------------------------------------------------------------------
        // ClassWorld
        // ----------------------------------------------------------------------------

        classWorld = c.getClassWorld();

        // Make sure we have a valid ClassWorld
        if ( classWorld == null )
        {
            classWorld = new ClassWorld( DEFAULT_REALM_NAME, Thread.currentThread().getContextClassLoader() );
        }

        containerRealm = c.getRealm();

        if ( containerRealm == null )
        {
            try
            {
                containerRealm = classWorld.getRealm( DEFAULT_REALM_NAME );
            }
            catch ( NoSuchRealmException e )
            {
                containerRealm = (ClassRealm) classWorld.getRealms().iterator().next();

                if ( containerRealm == null )
                {
                    System.err.println( "No container realm! Expect errors." );

                    new Throwable().printStackTrace();
                }
            }
        }

        setLookupRealm( containerRealm );

        // ----------------------------------------------------------------------------
        // Context
        // ----------------------------------------------------------------------------

        if ( c.getContext() != null )
        {
            containerContext = new DefaultContext( c.getContext() );
        }
        else
        {
            containerContext = new DefaultContext();
        }

        // ----------------------------------------------------------------------------
        // Configuration
        // ----------------------------------------------------------------------------

        InputStream in = null;

        if ( c.getContainerConfiguration() != null )
        {
            in = toStream( c.getContainerConfiguration() );
        }

        try
        {
            if ( c.getContainerConfigurationURL() != null )
            {
                in = c.getContainerConfigurationURL().openStream();
            }
        }
        catch ( IOException e )
        {
            throw new PlexusContainerException( "Error reading configuration URL", e );
        }

        try
        {
            configurationReader = in == null ? null : ReaderFactory.newXmlReader( in );
        }
        catch ( IOException e )
        {
            throw new PlexusContainerException( "Error reading configuration file", e );
        }

        try
        {
            initialize( c );

            start();
        }
        finally
        {
            IOUtil.close( configurationReader );
        }
    }

    // ----------------------------------------------------------------------------
    // Lookup
    // ----------------------------------------------------------------------------

    private Class<?> getInterfaceClass( String role, String hint )
    {
        if ( hint == null ) hint = PLEXUS_DEFAULT_HINT;

        try
        {
            ClassRealm realm = getLookupRealm();

            if ( realm != null )
            {
                return realm.loadClass( role );
            }
        }
        catch ( Throwable e )
        {
        }

        try
        {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if ( loader != null )
            {
                return loader.loadClass( role );
            }
        }
        catch ( Throwable e )
        {
        }

        try
        {
            ComponentDescriptor<?> cd = componentRegistry.getComponentDescriptor( Object.class, hint );
            if ( cd != null )
            {
                ClassLoader loader = cd.getImplementationClass().getClassLoader();
                if ( loader != null )
                {
                    return loader.loadClass( role );
                }
            }
        }
        catch ( Throwable ignored )
        {
        }

        return Object.class;
    }
    
    private Class<?> getRoleClass( String role )
    {
        return getInterfaceClass( role, null );        
    }

    private Class<?> getRoleClass( String role, String hint )
    {
        return getInterfaceClass( role, hint );
    }

    public Object lookup( String role ) throws ComponentLookupException
    {
        return componentRegistry.lookup( getRoleClass( role ), PLEXUS_DEFAULT_HINT );
    }

    public Object lookup( String role, String roleHint ) throws ComponentLookupException
    {
        return componentRegistry.lookup( getRoleClass( role, roleHint ), roleHint );
    }

    public <T> T lookup( Class<T> type ) throws ComponentLookupException
    {
        return componentRegistry.lookup( type, PLEXUS_DEFAULT_HINT );
    }

    public <T> T lookup( Class<T> type, String roleHint ) throws ComponentLookupException
    {
        return componentRegistry.lookup( type, roleHint );
    }

    @Deprecated
    public <T> T lookup( Class<T> type, String role, String roleHint ) throws ComponentLookupException
    {
        return componentRegistry.lookup( type, roleHint );
    }

    public <T> T lookup( ComponentDescriptor<T> descriptor ) throws ComponentLookupException
    {
        return componentRegistry.lookup( descriptor );
    }

    public List<Object> lookupList( String role ) throws ComponentLookupException
    {
        return cast(componentRegistry.lookupList( getRoleClass( role ), null));
    }

    public List<Object> lookupList( String role, List<String> roleHints ) throws ComponentLookupException
    {
        return cast(componentRegistry.lookupList( getRoleClass( role ), roleHints ));
    }

    public <T> List<T> lookupList( Class<T> type ) throws ComponentLookupException
    {
        return componentRegistry.lookupList( type, null );
    }

    public <T> List<T> lookupList( Class<T> type, List<String> roleHints ) throws ComponentLookupException
    {
        return componentRegistry.lookupList( type, roleHints );
    }

    public Map<String, Object> lookupMap( String role ) throws ComponentLookupException
    {
        return cast(componentRegistry.lookupMap(  getRoleClass( role ), null ));
    }

    public Map<String, Object> lookupMap( String role, List<String> roleHints ) throws ComponentLookupException
    {
        return cast(componentRegistry.lookupMap( getRoleClass( role ), roleHints ));
    }

    public <T> Map<String, T> lookupMap( Class<T> type ) throws ComponentLookupException
    {
        return componentRegistry.lookupMap( type, null );
    }

    public <T> Map<String, T> lookupMap( Class<T> type, List<String> roleHints ) throws ComponentLookupException
    {
        return componentRegistry.lookupMap( type, roleHints );
    }

    // ----------------------------------------------------------------------
    // Component Descriptor Lookup
    // ----------------------------------------------------------------------

    public boolean hasComponent( String role )
    {
        return componentRegistry.getComponentDescriptor( getRoleClass( role ), PLEXUS_DEFAULT_HINT ) != null;
    }

    public boolean hasComponent( String role, String roleHint )
    {
        return componentRegistry.getComponentDescriptor( getRoleClass( role ), roleHint ) != null;
    }

    public boolean hasComponent( Class<?> type )
    {
        return componentRegistry.getComponentDescriptor( type, PLEXUS_DEFAULT_HINT ) != null;
    }

    public boolean hasComponent( Class<?> type, String roleHint )
    {
        return componentRegistry.getComponentDescriptor( type, roleHint ) != null;
    }

    public ComponentDescriptor<?> getComponentDescriptor( String role )
    {
        return componentRegistry.getComponentDescriptor( getRoleClass( role ), PLEXUS_DEFAULT_HINT );
    }

    public ComponentDescriptor<?> getComponentDescriptor( String role, String roleHint )
    {
        return componentRegistry.getComponentDescriptor( getRoleClass( role ), roleHint );
    }

    public <T> ComponentDescriptor<T> getComponentDescriptor( Class<T> type )
    {
        return componentRegistry.getComponentDescriptor( type, PLEXUS_DEFAULT_HINT );
    }

    public <T> ComponentDescriptor<T> getComponentDescriptor( Class<T> type, String roleHint )
    {
        return componentRegistry.getComponentDescriptor( type, roleHint );
    }

    public Map<String, ComponentDescriptor<?>> getComponentDescriptorMap( String role )
    {
        return cast(componentRegistry.getComponentDescriptorMap( getRoleClass( role ) ));
    }

    @Deprecated
    public <T> Map<String, ComponentDescriptor<T>> getComponentDescriptorMap( Class<T> type, String role )
    {
        return componentRegistry.getComponentDescriptorMap( type );
    }

    public List<ComponentDescriptor<?>> getComponentDescriptorList( String role )
    {
        return cast(componentRegistry.getComponentDescriptorList( getRoleClass( role ) ));
    }

    public <T> List<ComponentDescriptor<T>> getComponentDescriptorList( Class<T> type )
    {
        return componentRegistry.getComponentDescriptorList( type );
    }

    public void addComponentDescriptor( ComponentDescriptor<?> componentDescriptor ) throws ComponentRepositoryException
    {
        if ( componentDescriptor.getRealm() == null )
        {
            componentDescriptor.setRealm( this.containerRealm );
            // throw new ComponentImplementationNotFoundException( "ComponentDescriptor is missing realmId" );
        }
        componentRegistry.addComponentDescriptor( componentDescriptor );
    }

    // ----------------------------------------------------------------------
    // Component Release
    // ----------------------------------------------------------------------

    public void release( Object component )
        throws ComponentLifecycleException
    {
        componentRegistry.release( component );
    }

    public void releaseAll( Map<String, ?> components )
        throws ComponentLifecycleException
    {
        for ( Object component : components.values() )
        {
            release( component );
        }
    }

    public void releaseAll( List<?> components )
        throws ComponentLifecycleException
    {
        for ( Object component : components )
        {
            release( component );
        }
    }

    // ----------------------------------------------------------------------
    // Lifecycle Management
    // ----------------------------------------------------------------------

    protected void initialize( ContainerConfiguration containerConfiguration )
        throws PlexusContainerException
    {
        try
        {
            initializeConfiguration( containerConfiguration );

            initializePhases( containerConfiguration );
            
            containerContext.put( PlexusConstants.PLEXUS_KEY, this );
            
            discoverComponents( getContainerRealm() );   

            PlexusConfiguration[] loadOnStartComponents = getConfiguration().getChild( "load-on-start" ).getChildren( "component" );

            getLogger().debug( "Found " + loadOnStartComponents.length + " components to load on start" );

            ClassLoader prevCl = Thread.currentThread().getContextClassLoader();

            try
            {
                for ( PlexusConfiguration loadOnStartComponent : loadOnStartComponents )
                {
                    String role = loadOnStartComponent.getChild( "role" ).getValue( null );

                    String roleHint = loadOnStartComponent.getChild( "role-hint" ).getValue( null );

                    if ( role == null )
                    {
                        throw new PlexusContainerException( "Missing 'role' element from load-on-start." );
                    }

                    if ( roleHint == null )
                    {
                        roleHint = PlexusConstants.PLEXUS_DEFAULT_HINT;
                    }

                    if ( roleHint.equals( "*" ) )
                    {
                        getLogger().info( "Loading on start all components with [role]: " + "[" + role + "]" );

                        lookupList( role );
                    }
                    else
                    {
                        getLogger().info( "Loading on start [role,roleHint]: " + "[" + role + "," + roleHint + "]" );

                        lookup( role, roleHint );
                    }
                }
            }
            catch ( ComponentLookupException e )
            {
                throw new PlexusContainerException( "Error looking up load-on-start component.", e );
            }
            finally
            {
                Thread.currentThread().setContextClassLoader( prevCl );
            }

        }
        catch ( ContextException e )
        {
            throw new PlexusContainerException( "Error processing configuration", e );
        }
        catch ( PlexusConfigurationException e )
        {
            throw new PlexusContainerException( "Error configuring components", e );
        }
        catch ( IOException e )
        {
            throw new PlexusContainerException( "Error reading configuration file", e );
        }
        catch ( ComponentRepositoryException e )
        {
            throw new PlexusContainerException( "Error discoverying components.", e );
        }        
    }

    protected void initializePhases( ContainerConfiguration containerConfiguration )
        throws PlexusContainerException
    {
        ContainerInitializationPhase[] initPhases = containerConfiguration.getInitializationPhases();

        ContainerInitializationContext initializationContext = new ContainerInitializationContext(
            this,
            classWorld,
            containerRealm,
            configuration,
            containerConfiguration );

        for ( ContainerInitializationPhase phase : initPhases )
        {
            try
            {
                phase.execute( initializationContext );
            }
            catch ( Exception e )
            {
                throw new PlexusContainerException( "Error initializaing container in " + phase.getClass().getName()
                    + ".", e );
            }
        }
    }

    protected void start()
        throws PlexusContainerException
    {
        // XXX this is called after initializeConfiguration - is this correct?
        configuration = null;
    }

    public void dispose()
    {
        try
        {
            componentRegistry.dispose();

            boolean needToDisposeRealm = false;

            try
            {
                containerRealm.setParentRealm( null );

                if ( needToDisposeRealm )
                {
                    classWorld.disposeRealm( containerRealm.getId() );
                }
            }
            catch ( NoSuchRealmException e )
            {
                getLogger().debug( "Failed to dispose realm." );
            }
        }
        finally
        {
            lookupRealm.set( null );
        }
    }

    public void addContextValue( Object key, Object value )
    {
        containerContext.put( key, value );
    }

    // ----------------------------------------------------------------------
    // Misc Configuration
    // ----------------------------------------------------------------------

    public ClassWorld getClassWorld()
    {
        return classWorld;
    }

    public void setClassWorld( ClassWorld classWorld )
    {
        this.classWorld = classWorld;
    }

    public ClassRealm getContainerRealm()
    {
        return containerRealm;
    }

    public void setContainerRealm( ClassRealm containerRealm )
    {
        this.containerRealm = containerRealm;
    }

    // ----------------------------------------------------------------------
    // Context
    // ----------------------------------------------------------------------

    public Context getContext()
    {
        return containerContext;
    }

    // ----------------------------------------------------------------------
    // ComponentListener
    // ----------------------------------------------------------------------

    public void addComponentDescriptorListener( ComponentDescriptorListener<?> listener )
    {
        componentRegistry.addComponentDescriptorListener( listener );
    }

    public void removeComponentDescriptorListener( ComponentDescriptorListener<?> listener )
    {
        componentRegistry.removeComponentDescriptorListener( listener );
    }

    // ----------------------------------------------------------------------
    // Configuration
    // ----------------------------------------------------------------------

    // TODO: put this in a separate helper class and turn into a component if possible, too big.

    protected void initializeConfiguration( ContainerConfiguration c )
        throws PlexusConfigurationException,
            ContextException,
            IOException
    {
        // We need an empty plexus configuration for merging. This is a function of removing the
        // plexus-boostrap.xml file.
        configuration = new XmlPlexusConfiguration( "plexus" );

            PlexusXmlComponentDiscoverer discoverer = new PlexusXmlComponentDiscoverer();

            PlexusConfiguration plexusConfiguration = discoverer.discoverConfiguration( getContext(), containerRealm );

            if ( plexusConfiguration != null )
            {
                configuration = PlexusConfigurationMerger.merge( plexusConfiguration, configuration );
            }

        if ( configurationReader != null )
        {
            // User userConfiguration

            PlexusConfiguration userConfiguration = PlexusTools.buildConfiguration(
                "<User Specified Configuration Reader>",
                getInterpolationConfigurationReader( configurationReader ) );

            // Merger of bootstrapConfiguration and user userConfiguration

            configuration = PlexusConfigurationMerger.merge( userConfiguration, configuration );
        }
    }

    protected Reader getInterpolationConfigurationReader( Reader reader )
    {
        return new InterpolationFilterReader( reader, new ContextMapAdapter( containerContext ) );
    }

    public Logger getLogger()
    {
        return super.getLogger();
    }

    // ----------------------------------------------------------------------
    // Discovery
    // ----------------------------------------------------------------------

    public void registerComponentDiscoveryListener( ComponentDiscoveryListener listener )
    {
        componentDiscovererManager.registerComponentDiscoveryListener( listener );
    }

    public void removeComponentDiscoveryListener( ComponentDiscoveryListener listener )
    {
        componentDiscovererManager.removeComponentDiscoveryListener( listener );
    }

    // ----------------------------------------------------------------------------
    // Mutable Container Interface
    // ----------------------------------------------------------------------------

    public ComponentRegistry getComponentRegistry()
    {
        return componentRegistry;
    }

    public void setComponentRegistry( ComponentRegistry componentRegistry )
    {
        this.componentRegistry = componentRegistry;
    }

    public ComponentDiscovererManager getComponentDiscovererManager()
    {
        return componentDiscovererManager;
    }

    public void setComponentDiscovererManager( ComponentDiscovererManager componentDiscovererManager )
    {
        this.componentDiscovererManager = componentDiscovererManager;
    }

    public ComponentFactoryManager getComponentFactoryManager()
    {
        return componentFactoryManager;
    }

    public void setComponentFactoryManager( ComponentFactoryManager componentFactoryManager )
    {
        this.componentFactoryManager = componentFactoryManager;
    }

    // Configuration

    public PlexusConfiguration getConfiguration()
    {
        return configuration;
    }

    public void setConfiguration( PlexusConfiguration configuration )
    {
        this.configuration = configuration;
    }

    // ----------------------------------------------------------------------------
    // Component Realms
    // ----------------------------------------------------------------------------

    public ClassRealm getComponentRealm( String realmId )
    {
        ClassRealm realm = null;

        try
        {
            realm = classWorld.getRealm( realmId );
        }
        catch ( NoSuchRealmException e )
        {
            // This should never happen: when a component is discovered, it is discovered from a realm and
            // it is at that point the realm id is assigned to the component descriptor.
        }

        if ( realm == null )
        {
            // The core components need the container realm.
            realm = containerRealm;
        }

        return realm;
    }

    public void removeComponentRealm( ClassRealm realm )
        throws PlexusContainerException
    {
        if ( getContainerRealm().getId().equals( realm.getId() ) )
        {
            throw new IllegalArgumentException( "Cannot remove container realm: " + realm.getId()
                + "\n(trying to remove container realm as if it were a component realm)." );
        }

        componentRegistry.removeComponentRealm( realm );

        ClassRealm lookupRealm = getLookupRealm();
        if ( ( lookupRealm != null ) && lookupRealm.getId().equals( realm.getId() ) )
        {
            setLookupRealm( getContainerRealm() );
        }
    }

    private InputStream toStream( String resource )
        throws PlexusContainerException
    {
        if ( resource == null )
        {
            return null;
        }

        String relativeResource = resource;
        if ( resource.startsWith( "/" ) )
        {
            relativeResource = resource.substring( 1 );
        }

        InputStream is = getClass().getClassLoader().getResourceAsStream( relativeResource );

        if ( is == null )
        {
            try
            {
                return new FileInputStream( resource );
            }
            catch ( FileNotFoundException e )
            {
                return null;
            }
        }

        return is;
    }

    /**
     * Utility method to get a default lookup realm for a component.
     */
    public ClassRealm getLookupRealm( Object component )
    {
        if ( component.getClass().getClassLoader() instanceof ClassRealm )
        {
            return ( (ClassRealm) component.getClass().getClassLoader() );
        }
        else
        {
            return getLookupRealm();
        }

    }

    public void setConfigurationSource( ConfigurationSource configurationSource )
    {
        this.configurationSource = configurationSource;
    }

    public ConfigurationSource getConfigurationSource()
    {
        return configurationSource;
    }

    public LoggerManager getLoggerManager()
    {
        // TODO Auto-generated method stub
        return loggerManager;
    }

    public void setLoggerManager( LoggerManager loggerManager )
    {
        this.loggerManager = loggerManager;

    }
    
    // Discovery

    public List<ComponentDescriptor<?>> discoverComponents( ClassRealm realm )
        throws PlexusConfigurationException, ComponentRepositoryException
    {

        List<ComponentSetDescriptor> componentSets = new ArrayList<ComponentSetDescriptor>();
        for ( ComponentDiscoverer componentDiscoverer : getComponentDiscovererManager().getComponentDiscoverers() )
        {
            for ( ComponentSetDescriptor componentSet : componentDiscoverer.findComponents( getContext(), realm ) )
            {
                componentSets.add(componentSet);
            }
        }

        List<ComponentDescriptor<?>> discoveredComponentDescriptors = new ArrayList<ComponentDescriptor<?>>();
        for ( ComponentSetDescriptor componentSet : componentSets )
        {
            for ( ComponentDescriptor<?> componentDescriptor : componentSet.getComponents() )
            {
                addComponentDescriptor( componentDescriptor );

                discoveredComponentDescriptors.add( componentDescriptor );
            }
        }
        return discoveredComponentDescriptors;
    }
}
